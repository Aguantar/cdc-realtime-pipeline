{{ config(materialized='table', order_by='(hour_utc, coin_id)') }}
-- 김치 프리미엄 (docs/34 #4): Upbit KRW 가격 ÷ (Binance USDT 가격 × USDT/KRW) − 1. **거래소가 다르기 때문에 성립하는** 유일한 가격 비교.
-- 어제(docs/28 C) "가격은 비교하지 않는다"의 정정: 통화·환율·코인 차원이 갖춰지면 비교할 수 있다. 조건 = 같은 UTC 시간, 두 거래소 모두 체결 ≥ 10건, 환율은 Upbit KRW-USDT 시간 종가.
WITH px AS (
    SELECT coin_id, hour_utc, anyIf(close, venue = 'upbit') AS upbit_krw, anyIf(close, venue = 'binance') AS binance_usdt,
           anyIf(trades, venue = 'upbit') AS upbit_trades, anyIf(trades, venue = 'binance') AS binance_trades
    FROM {{ ref('int_venue_hourly_close') }} GROUP BY coin_id, hour_utc
    HAVING upbit_trades >= 10 AND binance_trades >= 10
)
SELECT p.hour_utc AS hour_utc, p.coin_id AS coin_id, p.upbit_krw AS upbit_krw, p.binance_usdt AS binance_usdt, f.usdt_krw_close AS usdt_krw,
       p.binance_usdt * f.usdt_krw_close AS binance_krw_equiv,
       round(100 * (p.upbit_krw / (p.binance_usdt * f.usdt_krw_close) - 1), 3) AS premium_pct,
       p.upbit_trades AS upbit_trades, p.binance_trades AS binance_trades
FROM px AS p INNER JOIN {{ ref('int_fx_usdt_krw_hourly') }} AS f ON f.hour_utc = p.hour_utc
WHERE f.usdt_krw_close > 0 AND p.binance_usdt > 0
