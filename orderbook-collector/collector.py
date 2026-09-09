"""
Upbit WebSocket orderbook → Kafka collector
===========================================
KRW 전 마켓 호가(orderbook, count=15 기본) 스냅샷을 수신해 Kafka 토픽으로 직접 발행한다.
체결(trade) producer와 달리 MySQL/Debezium을 거치지 않는다(호가는 체결의 14배 건수·48배 바이트, 2026-09-09 실측).

설계 근거(사전 검증 2026-09-09):
- 단일 커넥션으로 287마켓 orderbook.15 구독 가능(누락 0). 메시지는 항상 전체 스냅샷.
- WS 연결 한도 5회/초/IP(초과 시 429) → 재연결은 지수 백오프.
- 1차 producer의 교훈: 처리 상한·버퍼 잔량이 보이지 않으면 지연을 못 본다 → STATS에 큐 잔량·지연 p50/p95 출력.
"""
import asyncio
import json
import logging
import os
import signal
import sys
import time
import urllib.request
import uuid

import websockets
from confluent_kafka import Producer, KafkaException

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s',
                    datefmt='%Y-%m-%d %H:%M:%S')
logger = logging.getLogger('orderbook-collector')

UPBIT_WS_URL = 'wss://api.upbit.com/websocket/v1'
UPBIT_MARKET_URL = 'https://api.upbit.com/v1/market/all?is_details=false'

KAFKA_BOOTSTRAP = os.getenv('KAFKA_BOOTSTRAP', 'kafka-1:29092,kafka-2:29093,kafka-3:29094')
TOPIC = os.getenv('ORDERBOOK_TOPIC', 'upbit.orderbook.v1')
COUNT = int(os.getenv('ORDERBOOK_COUNT', '15'))            # 호가 단 수 (1/5/15/30)
QUOTE = os.getenv('QUOTE_CURRENCY', 'KRW')
MARKETS_ENV = os.getenv('MARKETS', '')                      # 지정 시 REST 조회 생략 (콤마 구분)
STATS_INTERVAL = int(os.getenv('STATS_INTERVAL_SEC', '30'))
QUEUE_WARN = int(os.getenv('QUEUE_WARN_MSGS', '20000'))     # librdkafka 큐 잔량 경고 임계
WS_PING_INTERVAL = 30
WS_PING_TIMEOUT = 10


class Stats:
    def __init__(self):
        self.received = 0
        self.produced = 0          # delivery 성공
        self.delivery_errors = 0
        self.buffer_errors = 0     # 로컬 큐 가득 참
        self.bytes = 0
        self.lat = []              # recv_ts - tms (ms), 구간 내
        self.reconnects = 0
        self.start = time.time()
        self.last = time.time()
        self.last_queue_warn = 0.0

    def report(self, queue_len, markets):
        now = time.time()
        dt = now - self.last
        lat = sorted(self.lat)
        p50 = lat[len(lat) // 2] if lat else 0
        p95 = lat[int(len(lat) * 0.95)] if lat else 0
        logger.info(
            f"[STATS] recv={self.received} produced={self.produced} deliv_err={self.delivery_errors} "
            f"buf_err={self.buffer_errors} queue={queue_len} rate={self.received_interval / dt if dt > 0 else 0:.1f}/s "
            f"bytes={self.bytes_interval / dt / 1024 if dt > 0 else 0:.1f}KB/s lag_p50={p50:.0f}ms lag_p95={p95:.0f}ms "
            f"markets={markets} reconnects={self.reconnects} uptime={now - self.start:.0f}s"
        )
        self.received_interval = 0
        self.bytes_interval = 0
        self.lat = []
        self.last = now

    received_interval = 0
    bytes_interval = 0


def fetch_markets():
    if MARKETS_ENV.strip():
        return [m.strip() for m in MARKETS_ENV.split(',') if m.strip()]
    req = urllib.request.Request(UPBIT_MARKET_URL, headers={'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.load(r)
    return sorted(m['market'] for m in data if m['market'].startswith(QUOTE + '-'))


def make_producer():
    conf = {
        'bootstrap.servers': KAFKA_BOOTSTRAP,
        'client.id': 'orderbook-collector',
        'enable.idempotence': True,        # acks=all, retries, max.in.flight=5 자동
        'compression.type': 'zstd',
        'linger.ms': 50,
        'batch.num.messages': 2000,
        'queue.buffering.max.messages': 200000,
        'queue.buffering.max.kbytes': 262144,
        'message.timeout.ms': 120000,
    }
    return Producer(conf)


async def run(stats, shutdown):
    producer = make_producer()
    markets = fetch_markets()
    logger.info(f"마켓 {len(markets)}개, 토픽 {TOPIC}, count={COUNT}, bootstrap={KAFKA_BOOTSTRAP}")

    def on_delivery(err, msg):
        if err is not None:
            stats.delivery_errors += 1
            if stats.delivery_errors <= 5 or stats.delivery_errors % 1000 == 0:
                logger.error(f"delivery 실패: {err}")
        else:
            stats.produced += 1

    backoff = 1
    last_stats = time.time()
    while not shutdown.is_set():
        sub = [
            {"ticket": str(uuid.uuid4())[:8]},
            {"type": "orderbook", "codes": [f"{m}.{COUNT}" for m in markets]},
            {"format": "SIMPLE"},
        ]
        try:
            logger.info("Upbit WebSocket 연결 중...")
            async with websockets.connect(UPBIT_WS_URL, ping_interval=WS_PING_INTERVAL,
                                          ping_timeout=WS_PING_TIMEOUT, max_size=2 ** 23) as ws:
                await ws.send(json.dumps(sub))
                logger.info("연결 완료, 호가 수신 시작")
                backoff = 1
                while not shutdown.is_set():
                    try:
                        raw = await asyncio.wait_for(ws.recv(), timeout=1.0)
                    except asyncio.TimeoutError:
                        raw = None
                    if raw is not None:
                        now_ms = int(time.time() * 1000)
                        data = json.loads(raw if isinstance(raw, str) else raw.decode('utf-8'))
                        if data.get('ty') == 'orderbook':
                            data['rts'] = now_ms                  # 수집기 수신 시각
                            value = json.dumps(data, separators=(',', ':')).encode('utf-8')
                            stats.received += 1
                            stats.received_interval += 1
                            stats.bytes += len(value)
                            stats.bytes_interval += len(value)
                            tms = data.get('tms')
                            if isinstance(tms, int):
                                stats.lat.append(now_ms - tms)
                            try:
                                producer.produce(TOPIC, key=data.get('cd', '').encode(), value=value,
                                                 timestamp=tms if isinstance(tms, int) else 0,
                                                 on_delivery=on_delivery)
                            except BufferError:
                                stats.buffer_errors += 1
                                producer.poll(0.1)   # 큐 비울 시간
                                try:
                                    producer.produce(TOPIC, key=data.get('cd', '').encode(), value=value,
                                                     timestamp=tms if isinstance(tms, int) else 0,
                                                     on_delivery=on_delivery)
                                except BufferError:
                                    stats.delivery_errors += 1
                        elif 'error' in data:
                            logger.error(f"Upbit 에러 메시지: {data}")
                    producer.poll(0)
                    now = time.time()
                    if now - last_stats >= STATS_INTERVAL:
                        qlen = len(producer)
                        if qlen > QUEUE_WARN and now - stats.last_queue_warn >= 60:
                            logger.warning(f"Kafka 발행 큐 적체: {qlen}건")
                            stats.last_queue_warn = now
                        stats.report(qlen, len(markets))
                        last_stats = now
        except websockets.exceptions.InvalidStatus as e:
            code = getattr(getattr(e, 'response', None), 'status_code', '?')
            logger.warning(f"WebSocket 핸드셰이크 거부 (HTTP {code}). {backoff}초 후 재연결")
        except websockets.exceptions.ConnectionClosed as e:
            logger.warning(f"WebSocket 연결 끊김: {e}. {backoff}초 후 재연결")
        except Exception as e:
            logger.error(f"WebSocket 에러: {e!r}. {backoff}초 후 재연결")
        if not shutdown.is_set():
            stats.reconnects += 1
            producer.poll(0)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30)   # 1,2,4,...,30초 — 연결 한도 5/s 준수
    logger.info("종료: Kafka 큐 flush 중...")
    producer.flush(30)
    stats.report(len(producer), len(markets))


async def main():
    stats = Stats()
    shutdown = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, shutdown.set)
    try:
        await run(stats, shutdown)
    except KafkaException as e:
        logger.error(f"Kafka 치명적 오류: {e}")
        sys.exit(1)


if __name__ == '__main__':
    asyncio.run(main())
