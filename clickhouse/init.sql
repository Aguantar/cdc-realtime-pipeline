CREATE DATABASE IF NOT EXISTS cdc_pipeline;
USE cdc_pipeline;

-- ============================================
-- 1. Raw 체결 이벤트 (Flink → ClickHouse)
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_trades
(
    op              String,
    trade_id        UInt64,
    market          String,           -- KRW-BTC, KRW-ETH 등
    trade_price     Float64,          -- 체결 가격 (KRW)
    trade_volume    Float64,          -- 체결 수량
    trade_amount    Float64,          -- 체결 금액 (price × volume)
    ask_bid         String,           -- ASK(매도) / BID(매수)
    upbit_timestamp Int64,            -- Upbit 체결 시각 (Unix ms)
    sequential_id   Int64,            -- Upbit 체결 고유 ID
    source_ts       DateTime64(3),    -- MySQL 변경 시각
    cdc_ts          DateTime64(3),    -- Debezium 처리 시각
    cdc_latency_ms  Int64,            -- CDC 레이턴시
    flink_ts        DateTime64(3),    -- Flink 처리 시각
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(source_ts)
ORDER BY (market, source_ts, trade_id)
TTL toDateTime(source_ts) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


-- ============================================
-- 2. 5분 윈도우 집계 (Flink Window → ClickHouse)
-- ============================================
CREATE TABLE IF NOT EXISTS trade_aggregations
(
    market          String,
    window_start    DateTime64(3),
    window_end      DateTime64(3),
    trade_count     UInt64,
    bid_count       UInt64,
    ask_count       UInt64,
    total_amount    Float64,
    total_volume    Float64,
    avg_price       Float64,
    min_price       Float64,
    max_price       Float64,
    vwap            Float64,          -- 거래량 가중 평균 가격
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (market, window_start)
TTL toDateTime(window_start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


-- ============================================
-- 3. 이상 탐지 알림 (Flink Anomaly → ClickHouse)
-- ============================================
CREATE TABLE IF NOT EXISTS anomaly_alerts
(
    alert_type      String,           -- LARGE_TRADE, PRICE_SPIKE, VOLUME_SURGE, RAPID_TRADES
    market          String,
    trade_id        UInt64,
    message         String,
    value           Float64,
    threshold       Float64,
    detected_at     DateTime64(3),
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(detected_at)
ORDER BY (market, detected_at, alert_type)
TTL toDateTime(detected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


-- ============================================
-- 4. E2E 레이턴시 모니터링용 Materialized View
-- ============================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_latency_stats
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMMDD(minute)
ORDER BY (minute)
AS
SELECT
    toStartOfMinute(source_ts) AS minute,
    avgState(cdc_latency_ms)   AS avg_latency,
    maxState(cdc_latency_ms)   AS max_latency,
    minState(cdc_latency_ms)   AS min_latency,
    countState()               AS event_count
FROM crypto_trades
WHERE op IN ('c', 'u', 'd')
GROUP BY minute;
