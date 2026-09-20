{{ config(order_by='day') }}
-- Binance 체결 대조 (docs/31 §3-2): 거래소 1h 캔들의 체결 수(n) vs 우리 binance_trades FINAL count, (symbol, hour) 셀.
-- Upbit 대조(dq_reconcile_daily)와 같은 원리: 가중 비율 + 셀 최소값 + 0행 셀. 우리 > 거래소 는 중복(RMT 미머지)이나 시각 경계 문제, 우리 < 거래소 는 유실.
WITH ours AS (
    SELECT symbol, toStartOfHour(fromUnixTimestamp64Milli(trade_ms)) AS hour_utc, count() AS ours_n
    FROM {{ source('raw', 'binance_trades') }} FINAL
    GROUP BY symbol, hour_utc
),
ex AS (
    SELECT symbol, hour_utc, trade_count AS ex_n FROM {{ source('reference', 'binance_hourly_candles') }} FINAL
),
cells AS (
    SELECT e.symbol AS symbol, e.hour_utc AS hour_utc, e.ex_n AS ex_n, coalesce(o.ours_n, 0) AS ours_n,
           if(e.ex_n > 0, 100.0 * coalesce(o.ours_n, 0) / e.ex_n, NULL) AS ratio_pct
    FROM ex AS e LEFT JOIN ours AS o ON o.symbol = e.symbol AND o.hour_utc = e.hour_utc
)
SELECT toDate(hour_utc) AS day,
       round(100 * sum(ours_n) / sum(ex_n), 3) AS weighted_pct,
       count() AS cells, countIf(ratio_pct < 99) AS cells_below_99, countIf(ratio_pct > 101) AS cells_above_101, countIf(ours_n = 0 AND ex_n > 0) AS cells_no_rows,
       uniqExactIf(symbol, ours_n = 0 AND ex_n > 0) AS symbols_no_rows,
       round(min(ratio_pct), 2) AS min_cell_pct, argMin(concat(symbol, ' ', toString(hour_utc)), ratio_pct) AS worst_cell,
       now() AS computed_at
FROM cells WHERE ex_n > 0
GROUP BY day
