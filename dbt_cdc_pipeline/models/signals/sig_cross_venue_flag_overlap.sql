{{ config(materialized='table', order_by='(day, market)') }}
-- 교차 거래소 신호 (docs/28 C-3): 우리 Binance 주문이 "같은 코인"의 Upbit 경보 구간 안에 들어간 건수.
-- 같은 시장이 아니다 — 거래소·통화·호가장이 다르다. 그래서 가격·체결 품질은 비교하지 않고, "경보 중인 코인을 우리가 사고팔았나"만 센다.
-- 실무의 자리: 리스크·컴플라이언스가 묻는 "외부 신호(거래소 경보) ↔ 내부 활동(우리 원장)" 결합. 코인 매핑 = 심볼에서 USDT 를 뗀 기준 자산 → KRW-<자산>.
WITH orders AS (
    SELECT order_id, symbol, concat('KRW-', replaceOne(symbol, 'USDT', '')) AS market, side, status, executed_qty, cum_quote_qty, strategy,
           fromUnixTimestamp64Milli(created_ms) AS created_at
    FROM {{ source('raw', 'virtual_orders') }} FINAL
),
overlap AS (
    SELECT o.order_id, o.symbol, o.market, o.side, o.status, o.executed_qty, o.cum_quote_qty, o.strategy, o.created_at, f.flag, f.level, f.valid_from, f.valid_to, f.source
    FROM orders AS o
    INNER JOIN {{ ref('dim_market_flag_scd') }} AS f ON f.market = o.market
    WHERE o.created_at >= f.valid_from AND (f.valid_to IS NULL OR o.created_at < f.valid_to)
)
SELECT toDate(created_at) AS day, market, symbol, flag, level, source,
       count() AS orders_in_flag_window, countIf(status = 'FILLED') AS filled_in_flag_window,
       sum(cum_quote_qty) AS quote_in_flag_window, min(created_at) AS first_order_at, max(created_at) AS last_order_at,
       'cross-venue: Upbit flag interval vs Binance testnet orders, same coin' AS note
FROM overlap
GROUP BY day, market, symbol, flag, level, source
