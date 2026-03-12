{{
    config(
        materialized='table',
        order_by='market, trade_date'
    )
}}

-- 일봉 OHLCV 집계
SELECT
    market,
    trade_date,
    argMin(trade_price, trade_time_kst) AS open,
    max(trade_price) AS high,
    min(trade_price) AS low,
    argMax(trade_price, trade_time_kst) AS close,
    sum(trade_volume) AS volume,
    sum(trade_amount) AS amount,
    count(*) AS trade_count,
    countIf(ask_bid = 'BID') AS bid_count,
    countIf(ask_bid = 'ASK') AS ask_count,
    if(sum(trade_volume) > 0,
       sum(trade_amount) / sum(trade_volume),
       0
    ) AS vwap,
    -- 일중 변동폭 (%)
    if(min(trade_price) > 0,
       round((max(trade_price) - min(trade_price)) / min(trade_price) * 100, 2),
       0
    ) AS daily_range_pct
FROM {{ ref('stg_trades') }}
GROUP BY market, trade_date
