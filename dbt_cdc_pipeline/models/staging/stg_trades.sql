{{
    config(
        materialized='view'
    )
}}

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
FROM {{ source('raw', 'crypto_trades') }}
WHERE trade_price > 0
  AND trade_volume > 0
  AND op = 'c'  -- INSERT 이벤트만 (snapshot 'r' 제외하여 중복 방지)
