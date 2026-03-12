{{
    config(
        materialized='table',
        order_by='market, hour_start'
    )
}}

-- 1시간봉 OHLCV 집계
-- Flink의 5분 집계(trade_aggregations)와 역할 분리:
--   Flink = 실시간 5분 윈도우 (스트리밍)
--   DBT  = 배치 1시간봉 (raw 틱 데이터 기반, 더 정확한 OHLCV)
SELECT
    market,
    toStartOfHour(trade_time_kst) AS hour_start,
    argMin(trade_price, trade_time_kst) AS open,
    max(trade_price) AS high,
    min(trade_price) AS low,
    argMax(trade_price, trade_time_kst) AS close,
    sum(trade_volume) AS volume,
    sum(trade_amount) AS amount,
    count(*) AS trade_count,
    countIf(ask_bid = 'BID') AS bid_count,
    countIf(ask_bid = 'ASK') AS ask_count,
    -- VWAP (거래량 가중 평균 가격)
    if(sum(trade_volume) > 0,
       sum(trade_amount) / sum(trade_volume),
       0
    ) AS vwap
FROM {{ ref('stg_trades') }}
GROUP BY market, toStartOfHour(trade_time_kst)
