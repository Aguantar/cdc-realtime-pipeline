{{
    config(
        materialized='table',
        order_by='market, hour_start'
    )
}}

-- 직전 시간 대비 거래량 3배 이상 급등 감지
-- Flink의 VOLUME_SURGE(틱 단위, EWMA 150배)와 역할 분리:
--   Flink = 실시간 틱 단위 이상 탐지
--   DBT  = 배치 시간봉 단위 추세 분석
WITH hourly AS (
    SELECT
        market,
        hour_start,
        volume,
        amount,
        trade_count,
        open,
        high,
        low,
        close,
        vwap,
        lagInFrame(volume) OVER (
            PARTITION BY market ORDER BY hour_start
        ) AS prev_volume,
        lagInFrame(amount) OVER (
            PARTITION BY market ORDER BY hour_start
        ) AS prev_amount
    FROM {{ ref('int_ohlcv_1h') }}
)
SELECT
    market,
    hour_start,
    volume,
    amount,
    trade_count,
    open,
    high,
    low,
    close,
    vwap,
    prev_volume,
    prev_amount,
    round(volume / nullIf(prev_volume, 0), 2) AS volume_ratio,
    round(amount / nullIf(prev_amount, 0), 2) AS amount_ratio
FROM hourly
WHERE volume / nullIf(prev_volume, 0) >= 3.0
ORDER BY hour_start DESC
