{{ config(order_by='(day, symbol)') }}
-- 2층 원장 3자 대조 (docs/28 B-5): 거래소(REST) = MySQL = ClickHouse FINAL.
-- 거래소·MySQL 수는 생성기가 시간당 기록한 ledger_reconcile 의 하루 마지막 행, ClickHouse 수는 여기서 FINAL 로 센다.
-- 셋이 다 같아야 CDC 가 변경을 하나도 놓치지 않은 것. 하나라도 다르면 어느 구간이 틀렸는지 열 이름이 말해 준다(ex_/my_/ch_).
WITH last_rec AS (
    SELECT as_of_day AS day, symbol, argMax(ex_orders, reconciled_ms) AS ex_orders, argMax(ex_filled, reconciled_ms) AS ex_filled,
           argMax(ex_canceled, reconciled_ms) AS ex_canceled, argMax(ex_open, reconciled_ms) AS ex_open, argMax(ex_exec_qty, reconciled_ms) AS ex_exec_qty,
           argMax(ex_trades, reconciled_ms) AS ex_trades, argMax(ex_trade_qty, reconciled_ms) AS ex_trade_qty,
           argMax(my_orders, reconciled_ms) AS my_orders, argMax(my_filled, reconciled_ms) AS my_filled, argMax(my_exec_qty, reconciled_ms) AS my_exec_qty,
           argMax(my_trades, reconciled_ms) AS my_trades, argMax(my_trade_qty, reconciled_ms) AS my_trade_qty,
           argMax(mismatch, reconciled_ms) AS ex_my_mismatch, max(reconciled_ms) AS last_reconciled_ms
    FROM {{ source('raw', 'ledger_reconcile') }} FINAL
    GROUP BY as_of_day, symbol
),
ch_orders AS (
    SELECT toDate(fromUnixTimestamp64Milli(created_ms)) AS day, symbol, count() AS ch_orders, countIf(status = 'FILLED') AS ch_filled,
           countIf(status IN ('CANCELED', 'EXPIRED', 'EXPIRED_IN_MATCH', 'REJECTED')) AS ch_canceled, sum(executed_qty) AS ch_exec_qty
    FROM {{ source('raw', 'virtual_orders') }} FINAL
    GROUP BY day, symbol
),
ch_fills AS (
    SELECT toDate(fromUnixTimestamp64Milli(filled_ms)) AS day, symbol, count() AS ch_trades, sum(qty) AS ch_trade_qty
    FROM {{ source('raw', 'virtual_fills') }} FINAL
    GROUP BY day, symbol
)
SELECT
    r.day AS day, r.symbol AS symbol,
    r.ex_orders AS ex_orders, r.my_orders AS my_orders, o.ch_orders AS ch_orders,
    r.ex_filled AS ex_filled, r.my_filled AS my_filled, o.ch_filled AS ch_filled,
    r.ex_exec_qty AS ex_exec_qty, r.my_exec_qty AS my_exec_qty, o.ch_exec_qty AS ch_exec_qty,
    r.ex_trades AS ex_trades, r.my_trades AS my_trades, f.ch_trades AS ch_trades,
    r.ex_trade_qty AS ex_trade_qty, r.my_trade_qty AS my_trade_qty, f.ch_trade_qty AS ch_trade_qty,
    r.ex_my_mismatch AS ex_my_mismatch,
    toUInt8(r.my_orders != o.ch_orders OR r.my_filled != o.ch_filled OR r.my_exec_qty != o.ch_exec_qty OR r.my_trades != f.ch_trades OR r.my_trade_qty != f.ch_trade_qty) AS my_ch_mismatch,
    fromUnixTimestamp64Milli(r.last_reconciled_ms) AS reconciled_at
FROM last_rec AS r
LEFT JOIN ch_orders AS o ON o.day = r.day AND o.symbol = r.symbol
LEFT JOIN ch_fills AS f ON f.day = r.day AND f.symbol = r.symbol
