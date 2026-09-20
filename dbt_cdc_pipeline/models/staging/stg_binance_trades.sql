{{ config(materialized='view') }}
-- Binance 체결 스테이징 (docs/34 #4). FINAL: RMT(recv_ms) 재연결 중복 제거는 읽는 쪽 책임.
-- taker_side: Binance 는 is_buyer_maker(메이커 방향) 로 주므로 테이커 방향으로 뒤집는다 → Upbit ask_bid 와 같은 뜻(BID = 테이커 매수).
SELECT
    symbol, trade_id, price, qty, quote_qty,
    if(is_buyer_maker = 1, 'ASK', 'BID') AS taker_side,
    fromUnixTimestamp64Milli(trade_ms) AS trade_ts, trade_ms, recv_ms, flink_ts
FROM {{ source('raw', 'binance_trades') }} FINAL
