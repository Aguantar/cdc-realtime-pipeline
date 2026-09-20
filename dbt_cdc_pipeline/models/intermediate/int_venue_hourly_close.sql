{{ config(materialized='table', order_by='(coin_id, venue, hour_utc)') }}
-- 거래소별 시간 종가 (docs/34 #4): 교차 거래소 가격 비교의 재료. 같은 코인·같은 UTC 시간의 마지막 체결가.
-- 최근 14일만(비교는 최근이 목적, 전체 재계산은 1.2억 행).
WITH u AS (
    SELECT c.coin_id AS coin_id, 'upbit' AS venue, toStartOfHour(fromUnixTimestamp64Milli(t.upbit_timestamp)) AS hour_utc,
           argMax(t.trade_price, t.upbit_timestamp) AS close, count() AS trades, sum(t.trade_amount) AS amount_quote
    FROM {{ source('raw', 'crypto_trades') }} AS t FINAL
    INNER JOIN {{ ref('dim_coins') }} AS c ON c.upbit_market = t.market
    WHERE t.upbit_timestamp >= toUnixTimestamp(now() - INTERVAL 14 DAY) * 1000
    GROUP BY coin_id, hour_utc
),
b AS (
    SELECT c.coin_id AS coin_id, 'binance' AS venue, toStartOfHour(fromUnixTimestamp64Milli(t.trade_ms)) AS hour_utc,
           argMax(t.price, t.trade_ms) AS close, count() AS trades, sum(t.quote_qty) AS amount_quote
    FROM {{ source('raw', 'binance_trades') }} AS t FINAL
    INNER JOIN {{ ref('dim_coins') }} AS c ON c.binance_symbol = t.symbol
    WHERE t.trade_ms >= toUnixTimestamp(now() - INTERVAL 14 DAY) * 1000
    GROUP BY coin_id, hour_utc
)
SELECT * FROM u UNION ALL SELECT * FROM b
