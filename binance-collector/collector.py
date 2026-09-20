"""
Binance WebSocket trade → Kafka collector (docs/31 §3-2, 2026-09-20)
====================================================================
USDT·TRADING 전 심볼(실측 683)의 `trade` 스트림을 받아 Kafka `binance.trades.v1` 로 직접 발행한다(원장 없음 — 시세는 우리 데이터가 아니다).
설계 근거(실측·문서):
- 연결당 최대 1,024 스트림 → 연결당 ≤ STREAMS_PER_CONN(400) 으로 나눠 2~3 연결. 서버가 24시간에 끊으므로 23시간에 우리가 먼저 재연결(공백 시각을 우리가 고른다).
- 서버 ping 20초 → websockets 가 pong 을 자동으로 보낸다(ping_interval=None 으로 클라이언트 ping 은 끔).
- 24h 평균 361 msg/s(00:35 UTC 실측). producer 큐 20만 건 ≈ 9분치. Kafka key = symbol → 심볼 내 순서 보장.
- 심볼 목록 30분마다 갱신(신규 상장) → 바뀌면 그 연결만 재구독(재연결).
"""
import asyncio, json, logging, os, signal, time, urllib.request, collections
import websockets
from confluent_kafka import Producer, KafkaException

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s', datefmt='%Y-%m-%d %H:%M:%S')
log = logging.getLogger('binance-collector')

WS_BASE = os.getenv('BINANCE_WS_BASE', 'wss://stream.binance.com:9443/stream?streams=')
REST_URL = os.getenv('BINANCE_REST_URL', 'https://api.binance.com')
KAFKA_BOOTSTRAP = os.getenv('KAFKA_BOOTSTRAP', 'kafka-1:29092')
TOPIC = os.getenv('BINANCE_TRADES_TOPIC', 'binance.trades.v1')
QUOTE = os.getenv('QUOTE_ASSET', 'USDT')
STREAMS_PER_CONN = int(os.getenv('STREAMS_PER_CONN', '400'))
SYMBOL_REFRESH_SEC = float(os.getenv('SYMBOL_REFRESH_SEC', '1800'))
RECONNECT_BEFORE_SEC = float(os.getenv('RECONNECT_BEFORE_SEC', str(23 * 3600)))
STATS_INTERVAL = int(os.getenv('STATS_INTERVAL_SEC', '30'))
SYMBOLS_ENV = os.getenv('SYMBOLS', '')


def fetch_symbols():
    """USDT 견적·TRADING·현물 거래 가능 심볼. 실패 시 None(호출부가 기존 목록 유지). 100개 미만이면 응답 이상으로 보고 무시."""
    try:
        with urllib.request.urlopen(f"{REST_URL}/api/v3/exchangeInfo?permissions=SPOT", timeout=20) as r:
            info = json.load(r)
        syms = sorted(s['symbol'] for s in info['symbols'] if s['quoteAsset'] == QUOTE and s['status'] == 'TRADING' and s.get('isSpotTradingAllowed', True))
        if len(syms) < 100:
            log.warning(f"symbol list suspiciously small ({len(syms)}) — keeping previous"); return None
        return syms
    except Exception as e:
        log.warning(f"exchangeInfo failed: {e}"); return None


def chunks(lst, n):
    return [lst[i:i + n] for i in range(0, len(lst), n)]


class Stats:
    def __init__(self):
        self.recv = 0; self.produced = 0; self.deliv_err = 0; self.buf_err = 0; self.reconnects = 0; self.parse_err = 0
        self.lag_ms = collections.deque(maxlen=5000); self.last = time.time(); self.start = time.time()

    def report(self, producer, conns):
        q = len(producer)
        lags = sorted(self.lag_ms); p50 = lags[len(lags) // 2] if lags else 0; p95 = lags[int(len(lags) * 0.95)] if lags else 0
        log.info(f"[STATS] recv={self.recv} produced={self.produced} deliv_err={self.deliv_err} buf_err={self.buf_err} parse_err={self.parse_err} "
                 f"queue={q} conns={conns} reconnects={self.reconnects} lag_p50={p50}ms lag_p95={p95}ms uptime={int(time.time() - self.start)}s")
        self.last = time.time()


def make_producer():
    return Producer({'bootstrap.servers': KAFKA_BOOTSTRAP, 'enable.idempotence': True, 'compression.type': 'zstd', 'linger.ms': 50,
                     'batch.num.messages': 2000, 'queue.buffering.max.messages': 200000, 'queue.buffering.max.kbytes': 262144,
                     'message.timeout.ms': 600000})


class Conn:
    """스트림 묶음 하나 = WS 연결 하나. 23시간에 선제 재연결, 오류는 지수 백오프."""
    def __init__(self, idx, symbols, producer, stats, stop):
        self.idx = idx; self.symbols = symbols; self.producer = producer; self.stats = stats; self.stop = stop; self.resubscribe = asyncio.Event()

    def on_delivery(self, err, msg):
        if err: self.stats.deliv_err += 1
        else: self.stats.produced += 1

    async def run(self):
        backoff = 1
        while not self.stop.is_set():
            url = WS_BASE + '/'.join(s.lower() + '@trade' for s in self.symbols)
            opened = time.time()
            try:
                async with websockets.connect(url, ping_interval=None, max_size=2 ** 22) as ws:
                    log.info(f"conn#{self.idx} open: {len(self.symbols)} streams"); backoff = 1
                    while not self.stop.is_set() and not self.resubscribe.is_set() and time.time() - opened < RECONNECT_BEFORE_SEC:
                        try:
                            raw = await asyncio.wait_for(ws.recv(), timeout=30)
                        except asyncio.TimeoutError:
                            continue
                        recv_ms = int(time.time() * 1000)
                        try:
                            m = json.loads(raw); d = m['data']
                            if d.get('e') != 'trade': continue
                            d['recv_ms'] = recv_ms
                            self.producer.produce(TOPIC, key=d['s'], value=json.dumps(d, separators=(',', ':')), on_delivery=self.on_delivery)
                            self.stats.recv += 1; self.stats.lag_ms.append(recv_ms - int(d['T']))
                        except BufferError:
                            self.stats.buf_err += 1; self.producer.poll(0.1)
                        except (KeyError, ValueError, TypeError):
                            self.stats.parse_err += 1
                        self.producer.poll(0)
                    if self.resubscribe.is_set(): self.resubscribe.clear(); log.info(f"conn#{self.idx} resubscribe with {len(self.symbols)} streams")
                    elif not self.stop.is_set(): log.info(f"conn#{self.idx} planned reconnect after {int(time.time() - opened)}s")
            except Exception as e:
                log.warning(f"conn#{self.idx} error: {type(e).__name__}: {str(e)[:120]} — retry in {backoff}s")
                await asyncio.sleep(backoff); backoff = min(backoff * 2, 60)
            self.stats.reconnects += 1


async def main():
    stop = asyncio.Event(); loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM): loop.add_signal_handler(sig, stop.set)
    symbols = [s for s in SYMBOLS_ENV.split(',') if s] or fetch_symbols() or []
    if not symbols: log.error('no symbols'); return
    producer = make_producer(); stats = Stats()
    groups = chunks(symbols, STREAMS_PER_CONN); conns = [Conn(i, g, producer, stats, stop) for i, g in enumerate(groups)]
    log.info(f"symbols={len(symbols)} conns={len(conns)} topic={TOPIC} bootstrap={KAFKA_BOOTSTRAP}")
    tasks = [asyncio.create_task(c.run()) for c in conns]
    last_refresh = time.time()
    while not stop.is_set():
        await asyncio.sleep(1); producer.poll(0)
        if time.time() - stats.last >= STATS_INTERVAL: stats.report(producer, len(conns))
        if not SYMBOLS_ENV and time.time() - last_refresh >= SYMBOL_REFRESH_SEC:
            last_refresh = time.time(); new = fetch_symbols()
            if new and new != symbols:
                added = sorted(set(new) - set(symbols)); removed = sorted(set(symbols) - set(new))
                log.info(f"symbol list changed: +{added[:10]} -{removed[:10]} → resubscribe all conns")
                symbols = new; groups = chunks(symbols, STREAMS_PER_CONN)
                for i, c in enumerate(conns):
                    if i < len(groups): c.symbols = groups[i]; c.resubscribe.set()
                if len(groups) > len(conns):
                    for i in range(len(conns), len(groups)):
                        c = Conn(i, groups[i], producer, stats, stop); conns.append(c); tasks.append(asyncio.create_task(c.run()))
    log.info('stopping: flushing producer'); producer.flush(30)
    for t in tasks: t.cancel()


if __name__ == '__main__':
    asyncio.run(main())
