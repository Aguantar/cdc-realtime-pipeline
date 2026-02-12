-- ============================================
--   CDC Pipeline - ClickHouse 테이블 설계
-- ============================================

-- ClickHouse 엔진 선택 근거:
-- MergeTree: ClickHouse의 기본 엔진. 대량 INSERT + 고속 집계 쿼리에 최적화.
-- ORDER BY: 데이터 정렬 키. 쿼리 성능에 직접 영향.
-- PARTITION BY: 월별 파티셔닝으로 오래된 데이터 삭제(TTL) 효율화.

-- ============================================
-- 1. Raw CDC 이벤트 (Flink → ClickHouse)
-- ============================================

CREATE DATABASE IF NOT EXISTS cdc_pipeline;
USE cdc_pipeline;

CREATE TABLE IF NOT EXISTS raw_orders
(
    op              String,           -- 오퍼레이션: r(snapshot), c(insert), u(update), d(delete)
    order_id        UInt64,
    user_id         UInt64,
    symbol          String,           -- 종목코드
    order_type      String,           -- BUY / SELL
    quantity        UInt32,
    price           Float64,
    status          String,           -- PENDING / FILLED / CANCELLED / PARTIAL
    total_amount    Float64,          -- quantity × price (Flink에서 계산)
    source_ts       DateTime64(3),    -- MySQL 변경 시각 (ms 정밀도)
    cdc_ts          DateTime64(3),    -- Debezium 처리 시각
    cdc_latency_ms  Int64,            -- CDC 레이턴시 (cdc_ts - source_ts)
    flink_ts        DateTime64(3),    -- Flink 처리 시각
    inserted_at     DateTime64(3) DEFAULT now64(3)  -- ClickHouse 입력 시각
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(source_ts)
ORDER BY (symbol, source_ts, order_id)
TTL toDateTime(source_ts) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- ORDER BY 설명:
--   (symbol, source_ts, order_id)
--   → "특정 종목의 특정 시간대 주문 조회"가 가장 빈번한 쿼리 패턴
--   → symbol로 먼저 정렬하면 종목 필터링 시 스캔 범위가 크게 줄어듦
--
-- TTL 설명:
--   90일 후 자동 삭제. 16GB 서버에서 디스크 관리를 위해 필수.


-- ============================================
-- 2. 5분 윈도우 집계 (Flink Window → ClickHouse)
-- ============================================
CREATE TABLE IF NOT EXISTS order_aggregations
(
    symbol          String,
    window_start    DateTime64(3),
    window_end      DateTime64(3),
    order_count     UInt64,
    buy_count       UInt64,
    sell_count      UInt64,
    total_amount    Float64,
    avg_price       Float64,
    min_price       Float64,
    max_price       Float64,
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (symbol, window_start)
TTL toDateTime(window_start) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- ORDER BY 설명:
--   (symbol, window_start)
--   → Grafana에서 "종목별 시계열 차트"를 그릴 때 최적
--   → 특정 종목의 시간대별 집계를 빠르게 조회


-- ============================================
-- 3. 이상 탐지 알림 (Flink Anomaly → ClickHouse)
-- ============================================
CREATE TABLE IF NOT EXISTS anomaly_alerts
(
    alert_type      String,           -- LARGE_ORDER, HIGH_AMOUNT, PRICE_SPIKE, RAPID_ORDERS
    symbol          String,
    order_id        UInt64,
    message         String,
    value           Float64,          -- 탐지된 값
    threshold       Float64,          -- 임계값
    detected_at     DateTime64(3),    -- Flink에서 탐지한 시각
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(detected_at)
ORDER BY (symbol, detected_at, alert_type)
TTL toDateTime(detected_at) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;


-- ============================================
-- 4. E2E 레이턴시 모니터링용 Materialized View
-- ============================================
-- raw_orders가 INSERT될 때 자동으로 레이턴시 통계를 집계
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
FROM raw_orders
WHERE op IN ('c', 'u', 'd')   -- 스냅샷 제외 (레이턴시 의미 없음)
GROUP BY minute;
