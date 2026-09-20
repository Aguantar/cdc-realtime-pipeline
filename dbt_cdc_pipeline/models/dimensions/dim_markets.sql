{{ config(materialized='table', order_by='market') }}
-- 2026-09-20 (docs/34 #2): crypto_trades 는 ReplacingMergeTree — 중복은 '결국' 지워지므로 읽는 쪽이 FINAL 로 보장한다(재시작 뒤 머지 전 배치가 중복을 세지 않게)
-- 마켓 마스터 (docs/26 §4). 거래소 목록·이름·경보 플래그 + 상장일 근사 + 우리가 처음/마지막으로 본 체결 시각.
-- 쓰임: 규칙 평가의 신규 상장 96h 제외(거래소 예외 규정), 대조 분모(추후 통일), 커버리지 사유("상장 N일째, 우리 체결 없음").
-- seen_gap_days = 우리 첫 체결일 − 상장일: BFC 사고(상장 후 6일 무수집)가 이 숫자로 남는다.
WITH master AS (
    SELECT * FROM {{ source('reference', 'upbit_market_master') }} FINAL
),
ours AS (
    SELECT market, min(source_ts) AS first_seen_ours, max(source_ts) AS last_seen_ours
    FROM {{ source('raw', 'crypto_trades') }} FINAL GROUP BY market
),
flags AS (
    SELECT market, groupArrayIf(flag, state = 1) AS active_flags
    FROM (SELECT market, flag, argMax(state, observed_at) AS state FROM {{ source('reference', 'upbit_market_events') }} GROUP BY market, flag)
    GROUP BY market
)
SELECT m.market AS market, m.korean_name AS korean_name, m.english_name AS english_name, m.market_warning AS market_warning,
       m.listing_date_est AS listing_date_est, m.listing_date_source AS listing_date_source, m.lookback_days AS lookback_days,
       nullIf(o.first_seen_ours, toDateTime64(0, 3)) AS first_seen_ours,
       nullIf(o.last_seen_ours, toDateTime64(0, 3)) AS last_seen_ours,
       if(m.listing_date_est IS NULL OR o.market = '', NULL, dateDiff('day', m.listing_date_est, toDate(o.first_seen_ours))) AS seen_gap_days,
       -- 커버리지 공백: 우리가 전 마켓 수집을 시작한 2026-09-09 이후 상장(일봉 창 안)에만 의미가 있다. 그 전 상장은 우리 수집 시작일이 첫 체결이라 190 처럼 나온다(BTC 등 5코인은 02-13 부터)
       if(m.listing_date_source = 'daily_candle_first' AND m.listing_date_est >= toDate('2026-09-09') AND o.market != '',
          dateDiff('day', m.listing_date_est, toDate(o.first_seen_ours)), NULL) AS coverage_gap_days,
       coalesce(f.active_flags, []) AS active_flags,
       m.is_active AS is_active, m.fetched_at AS fetched_at
FROM master AS m
LEFT JOIN ours AS o ON o.market = m.market
LEFT JOIN flags AS f ON f.market = m.market
