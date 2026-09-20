{{ config(materialized='table', order_by='hour_utc') }}
-- USDT/KRW 시간 환율 (docs/34 #4): 외부 FX 소스 대신 Upbit KRW-USDT 마켓(하루 58k 체결)의 시간 종가·VWAP. 우리 데이터라 조건이 같다.
SELECT toStartOfHour(fromUnixTimestamp64Milli(upbit_timestamp)) AS hour_utc,
       argMax(trade_price, upbit_timestamp) AS usdt_krw_close,
       sum(trade_amount) / sum(trade_volume) AS usdt_krw_vwap, count() AS trades
FROM {{ source('raw', 'crypto_trades') }} FINAL
WHERE market = 'KRW-USDT' AND upbit_timestamp >= toUnixTimestamp(now() - INTERVAL 14 DAY) * 1000 AND trade_volume > 0
GROUP BY hour_utc
