-- Binance 체결 (docs/31 §3-2, 2026-09-20). 시세이지 원장이 아니다 — Kafka 직행 → Flink → 여기.
-- RMT(recv_ms): 수집기 재연결(23시간 선제·오류)에서 같은 체결이 두 번 올 수 있다(at-least-once). 키 = (symbol, trade_id) 가 거래소 정체성.
-- 일 파티션: 3,100만 행/일. TTL 30일: 1GB/일 이라 1년은 375GB(디스크 466GB) → 안 된다. 조회는 전부 체결 시각(trade_ms) 기준.
CREATE TABLE IF NOT EXISTS cdc_pipeline.binance_trades
(
    symbol          LowCardinality(String),
    trade_id        UInt64,
    price           Float64,
    qty             Float64,
    quote_qty       Float64,
    is_buyer_maker  UInt8,
    trade_ms        Int64 COMMENT 'T 거래소 체결 시각',
    event_ms        Int64 COMMENT 'E 거래소 이벤트 시각',
    recv_ms         Int64 COMMENT '수집기 수신 시각',
    flink_ts        DateTime64(3),
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(recv_ms)
PARTITION BY toDate(fromUnixTimestamp64Milli(trade_ms))
ORDER BY (symbol, trade_ms, trade_id)
TTL toDateTime(fromUnixTimestamp64Milli(trade_ms)) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;

-- 대조 정답: REST klines 1h 의 "체결 수"(n). reconcile_binance DAG 가 일 1회 적재.
CREATE TABLE IF NOT EXISTS cdc_pipeline.binance_hourly_candles
(
    symbol      LowCardinality(String),
    hour_utc    DateTime,
    open        Float64, high Float64, low Float64, close Float64,
    volume      Float64,
    quote_volume Float64,
    trade_count UInt32,
    fetched_at  DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(fetched_at)
ORDER BY (symbol, hour_utc)
TTL hour_utc + INTERVAL 60 DAY;
