"""
Upbit WebSocket → MySQL Producer
=================================
업비트 실시간 체결 데이터를 수신하여 MySQL에 저장한다.
Debezium이 MySQL binlog를 감시하여 CDC 파이프라인으로 전달.

Upbit WebSocket API:
- 엔드포인트: wss://api.upbit.com/websocket/v1
- 인증 불필요 (공개 시세 데이터)
- 체결(trade) 데이터를 구독하면 실시간으로 Push

사용법:
    python producer.py                          # 기본 5개 마켓
    python producer.py --markets KRW-BTC KRW-ETH  # 마켓 지정
    python producer.py --batch-size 50          # 배치 크기 변경
"""

import asyncio
import json
import uuid
import signal
import sys
import time
import logging
from datetime import datetime
from decimal import Decimal
from collections import deque
from argparse import ArgumentParser

import websockets
import mysql.connector
from mysql.connector import pooling

# ============================================
# 로깅 설정
# ============================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger('upbit-producer')

# ============================================
# 설정
# ============================================
DEFAULT_MARKETS = [
    'KRW-BTC',   # 비트코인
    'KRW-ETH',   # 이더리움
    'KRW-XRP',   # 리플
    'KRW-SOL',   # 솔라나
    'KRW-DOGE',  # 도지코인
]

UPBIT_WS_URL = 'wss://api.upbit.com/websocket/v1'

# MySQL 연결 설정 (환경변수 또는 기본값)
import os
MYSQL_CONFIG = {
    'host': os.getenv('MYSQL_HOST', 'cdc-mysql'),
    'port': int(os.getenv('MYSQL_PORT', '3306')),
    'user': os.getenv('MYSQL_USER', 'root'),
    'password': os.getenv('MYSQL_PASSWORD', 'cdc_root_2025'),
    'database': os.getenv('MYSQL_DATABASE', 'crypto_db'),
    'charset': 'utf8mb4',
    'autocommit': False,
}

# 배치 INSERT 설정
BATCH_SIZE = int(os.getenv('BATCH_SIZE', '20'))
BATCH_INTERVAL_SEC = float(os.getenv('BATCH_INTERVAL_SEC', '2.0'))

# WebSocket 재연결 설정
WS_RECONNECT_DELAY = 5       # 초
WS_PING_INTERVAL = 30        # 초
WS_PING_TIMEOUT = 10         # 초

# ============================================
# 통계 추적
# ============================================
class Stats:
    def __init__(self):
        self.received = 0       # WebSocket 수신 건수
        self.inserted = 0       # MySQL INSERT 성공 건수
        self.duplicates = 0     # 중복 건수 (IGNORE)
        self.errors = 0         # 에러 건수
        self.start_time = time.time()
        self.last_report = time.time()

    def report(self):
        now = time.time()
        elapsed = now - self.start_time
        rate = self.inserted / elapsed if elapsed > 0 else 0
        logger.info(
            f"[STATS] received={self.received}, inserted={self.inserted}, "
            f"duplicates={self.duplicates}, errors={self.errors}, "
            f"rate={rate:.1f}/sec, uptime={elapsed:.0f}s"
        )
        self.last_report = now

# ============================================
# MySQL 배치 INSERT
# ============================================
INSERT_SQL = """
    INSERT IGNORE INTO crypto_trades
        (market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id)
    VALUES
        (%s, %s, %s, %s, %s, %s, %s)
"""

class MySQLWriter:
    """MySQL 배치 INSERT를 담당하는 클래스.

    배치 INSERT를 사용하는 이유:
    - 건건 INSERT보다 10~50배 빠르다 (네트워크 왕복 감소)
    - MySQL의 InnoDB는 트랜잭션 단위로 binlog를 기록하므로,
      배치 COMMIT이 Debezium CDC 이벤트 생성에도 효율적이다.
    - INSERT IGNORE로 sequential_id 중복을 무시한다.
    """

    def __init__(self, config, stats):
        self.config = config
        self.stats = stats
        self.conn = None
        self.cursor = None
        self.buffer = deque()

    def connect(self):
        """MySQL 연결. 실패 시 재시도."""
        max_retries = 30
        for attempt in range(1, max_retries + 1):
            try:
                self.conn = mysql.connector.connect(**self.config)
                self.cursor = self.conn.cursor()
                logger.info(f"MySQL 연결 성공 ({self.config['host']}:{self.config['port']})")
                return
            except mysql.connector.Error as e:
                logger.warning(f"MySQL 연결 실패 (시도 {attempt}/{max_retries}): {e}")
                time.sleep(2)
        raise RuntimeError("MySQL 연결 불가")

    def reconnect(self):
        """연결이 끊어진 경우 재연결."""
        try:
            if self.cursor:
                self.cursor.close()
            if self.conn:
                self.conn.close()
        except Exception:
            pass
        self.connect()

    def add(self, trade):
        """버퍼에 체결 데이터 추가."""
        self.buffer.append(trade)

    def flush(self):
        """버퍼의 데이터를 MySQL에 배치 INSERT."""
        if not self.buffer:
            return 0

        batch = []
        while self.buffer and len(batch) < BATCH_SIZE:
            batch.append(self.buffer.popleft())

        try:
            self.cursor.executemany(INSERT_SQL, batch)
            affected = self.cursor.rowcount
            self.conn.commit()

            duplicates = len(batch) - affected
            self.stats.inserted += affected
            self.stats.duplicates += duplicates

            if affected > 0:
                logger.debug(f"INSERT {affected}건 (중복 {duplicates}건)")
            return affected

        except mysql.connector.Error as e:
            logger.error(f"INSERT 실패: {e}")
            self.stats.errors += 1
            self.conn.rollback()

            # 연결 끊김이면 재연결
            if not self.conn.is_connected():
                logger.info("MySQL 재연결 시도...")
                self.reconnect()
                # 실패한 배치를 버퍼 앞에 다시 넣기
                self.buffer.extendleft(reversed(batch))
            return 0

    def close(self):
        """남은 버퍼 flush 후 연결 종료."""
        while self.buffer:
            self.flush()
        try:
            if self.cursor:
                self.cursor.close()
            if self.conn:
                self.conn.close()
            logger.info("MySQL 연결 종료")
        except Exception:
            pass

# ============================================
# Upbit WebSocket 수신
# ============================================
def parse_trade(data):
    """Upbit 체결 데이터를 MySQL INSERT용 튜플로 변환.

    Upbit WebSocket 응답 필드:
    - cd (code): 마켓 코드 (KRW-BTC)
    - tp (trade_price): 체결 가격
    - tv (trade_volume): 체결 수량
    - ab (ask_bid): ASK(매도) / BID(매수)
    - ttms (trade_timestamp): 체결 시각 (Unix ms)
    - sid (sequential_id): 체결 고유 ID (문자열)
    """
    market = data['cd']
    price = Decimal(str(data['tp']))
    volume = Decimal(str(data['tv']))
    amount = price * volume
    ask_bid = data['ab']
    timestamp = data['ttms']
    seq_id = int(data['sid'])

    return (
        market,
        float(price),
        float(volume),
        float(amount),
        ask_bid,
        timestamp,
        seq_id,
    )

async def subscribe_upbit(markets, writer, stats, shutdown_event):
    """Upbit WebSocket에 연결하여 체결 데이터를 수신한다.

    재연결 로직:
    - WebSocket 연결이 끊어지면 5초 후 자동 재연결
    - Upbit 서버는 약 4시간마다 연결을 끊을 수 있음
    - 재연결 시 구독 메시지를 다시 보내야 함
    """
    # 구독 메시지
    subscribe_msg = [
        {"ticket": str(uuid.uuid4())[:8]},
        {
            "type": "trade",
            "codes": markets,
            "isOnlyRealtime": True,  # 스냅샷 제외, 실시간만
        },
        {"format": "SIMPLE"},  # 필드명 축약 (tp, tv, ab 등)
    ]

    while not shutdown_event.is_set():
        try:
            logger.info(f"Upbit WebSocket 연결 중... (마켓: {', '.join(markets)})")

            async with websockets.connect(
                UPBIT_WS_URL,
                ping_interval=WS_PING_INTERVAL,
                ping_timeout=WS_PING_TIMEOUT,
            ) as ws:
                # 구독 요청
                await ws.send(json.dumps(subscribe_msg))
                logger.info("Upbit WebSocket 연결 완료, 체결 데이터 수신 시작")

                # 배치 flush를 위한 타이머
                last_flush = time.time()

                while not shutdown_event.is_set():
                    try:
                        # timeout으로 주기적으로 flush 체크
                        raw = await asyncio.wait_for(
                            ws.recv(),
                            timeout=BATCH_INTERVAL_SEC
                        )

                        # Upbit은 바이너리(msgpack이 아닌 bytes) 또는 텍스트로 응답
                        if isinstance(raw, bytes):
                            data = json.loads(raw.decode('utf-8'))
                        else:
                            data = json.loads(raw)

                        # 체결 데이터만 처리
                        if data.get('ty') == 'trade':
                            trade = parse_trade(data)
                            writer.add(trade)
                            stats.received += 1

                    except asyncio.TimeoutError:
                        pass  # timeout은 정상 — flush 타이밍 체크용

                    # 배치 간격마다 flush
                    now = time.time()
                    if now - last_flush >= BATCH_INTERVAL_SEC:
                        writer.flush()
                        last_flush = now

                    # 30초마다 통계 출력
                    if now - stats.last_report >= 30:
                        stats.report()

        except websockets.exceptions.ConnectionClosed as e:
            logger.warning(f"WebSocket 연결 끊김: {e}. {WS_RECONNECT_DELAY}초 후 재연결...")
        except Exception as e:
            logger.error(f"WebSocket 에러: {e}. {WS_RECONNECT_DELAY}초 후 재연결...")

        if not shutdown_event.is_set():
            # 남은 버퍼 flush
            writer.flush()
            await asyncio.sleep(WS_RECONNECT_DELAY)

# ============================================
# 메인
# ============================================
async def main():
    parser = ArgumentParser(description='Upbit → MySQL Producer')
    parser.add_argument('--markets', nargs='+', default=DEFAULT_MARKETS,
                        help='추적할 마켓 코드 (기본: KRW-BTC KRW-ETH KRW-XRP KRW-SOL KRW-DOGE)')
    parser.add_argument('--batch-size', type=int, default=BATCH_SIZE,
                        help='배치 INSERT 크기 (기본: 20)')
    args = parser.parse_args()


    # batch_size는 환경변수로 설정

    logger.info("=" * 50)
    logger.info("  Upbit → MySQL Producer")
    logger.info(f"  마켓: {', '.join(args.markets)}")
    logger.info(f"  배치 크기: {BATCH_SIZE}")
    logger.info(f"  배치 간격: {BATCH_INTERVAL_SEC}초")
    logger.info(f"  MySQL: {MYSQL_CONFIG['host']}:{MYSQL_CONFIG['port']}")
    logger.info("=" * 50)

    # MySQL 연결
    stats = Stats()
    writer = MySQLWriter(MYSQL_CONFIG, stats)
    writer.connect()

    # Graceful shutdown
    shutdown_event = asyncio.Event()

    def signal_handler(sig, frame):
        logger.info(f"종료 신호 수신 ({sig}), 남은 데이터 flush 중...")
        shutdown_event.set()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        await subscribe_upbit(args.markets, writer, stats, shutdown_event)
    finally:
        writer.close()
        stats.report()
        logger.info("Producer 종료 완료")

if __name__ == '__main__':
    asyncio.run(main())
