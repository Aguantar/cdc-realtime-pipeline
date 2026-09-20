{{
    config(
        materialized='view'
    )
}}

-- 2026-09-20 (docs/34 #2): crypto_trades 는 ReplacingMergeTree — 중복은 '결국' 지워지므로 읽는 쪽이 FINAL 로 보장한다(재시작 뒤 머지 전 배치가 중복을 세지 않게)
-- raw 틱 데이터에서 필요한 필드 추출 + 타입 정제
-- upbit_timestamp는 Int64 (Unix ms)이므로 fromUnixTimestamp64Milli()로 변환
SELECT
    trade_id,
    market,
    trade_price,
    trade_volume,
    trade_amount,
    ask_bid,
    toTimeZone(
        fromUnixTimestamp64Milli(upbit_timestamp),
        'Asia/Seoul'
    ) AS trade_time_kst,
    toDate(
        toTimeZone(fromUnixTimestamp64Milli(upbit_timestamp), 'Asia/Seoul')
    ) AS trade_date,
    toHour(
        toTimeZone(fromUnixTimestamp64Milli(upbit_timestamp), 'Asia/Seoul')
    ) AS trade_hour,
    sequential_id,
    cdc_latency_ms,
    inserted_at
FROM {{ source('raw', 'crypto_trades') }} FINAL
WHERE trade_price > 0
  AND trade_volume > 0
  AND op = 'c'  -- INSERT 이벤트만 (snapshot 'r' 제외하여 중복 방지)
