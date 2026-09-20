{{ config(materialized='incremental', incremental_strategy='delete+insert', unique_key='day_utc', order_by='(market, minute)',
          query_settings={'max_memory_usage': 1200000000, 'max_bytes_before_external_group_by': 500000000}) }}
-- 체결 × 호가 분 단위 결합 마트 (2026-09-18, docs/19 §2-1 순서 3).
-- 왜: 거래소는 호가 이력을 주지 않는다 → 이 표는 우리만 가진 데이터다. 유동성 플래그(얇은 호가에서의 큰 체결·되튐)와
--     규칙 평가의 "왜 어긋났나"(스프레드·깊이) 설명이 붙을 자리. EURC 사례(docs/14: 얇은 호가 되튐, 스프레드 p90 3.09%)를 숫자로 검증한다.
-- 시간 축: 둘 다 거래소 시각(체결 upbit_timestamp, 호가 tms) 의 분. 체결은 RMT 라 FINAL 로 읽는다(재전송 중복 제거).
-- 단위: 깊이(ask/bid_depth15_avg)는 수량 → volume_over_depth15 = 그 분 체결 수량 / 15호가 평균 잔량(양쪽 합). 1 이면 "호가 전체만큼 체결".
-- 증분: 일 단위 delete+insert. 초기 적재는 mart_from/mart_to 로 하루씩(전체 FINAL 은 메모리 한도).
{% set d_from = var('mart_from', "toString(toDate(now()) - 1)") %}
{% set d_to   = var('mart_to',   "toString(toDate(now()))") %}
{% set day_from = "toDate(" ~ ("'" ~ d_from ~ "'" if d_from[:2] == '20' else d_from) ~ ")" %}
{% set day_to   = "toDate(" ~ ("'" ~ d_to   ~ "'" if d_to[:2]   == '20' else d_to)   ~ ")" %}

WITH trades AS (
    SELECT market,
           toStartOfMinute(fromUnixTimestamp64Milli(upbit_timestamp)) AS minute,
           count()                                        AS trade_count,
           sum(trade_volume)                              AS volume,
           sum(trade_amount)                              AS amount,
           sum(trade_amount) / nullIf(sum(trade_volume), 0) AS vwap,
           argMin(trade_price, (upbit_timestamp, sequential_id)) AS open,
           max(trade_price)                               AS high,
           min(trade_price)                               AS low,
           argMax(trade_price, (upbit_timestamp, sequential_id)) AS close,
           countIf(ask_bid = 'BID') / count()             AS buy_ratio
    FROM {{ source('raw', 'crypto_trades') }} FINAL
    WHERE op = 'c'
      AND upbit_timestamp >= toUnixTimestamp({{ day_from }}) * 1000
      AND upbit_timestamp <  toUnixTimestamp({{ day_to }} + INTERVAL 1 DAY) * 1000
    GROUP BY market, minute
),
book AS (
    SELECT market, window_start AS minute, snapshots, mid_open, mid_close, mid_min, mid_max,
           spread_bp_avg, spread_bp_max, imb1_avg, imb5_avg, imb15_avg, ask_depth15_avg, bid_depth15_avg
    FROM {{ source('raw', 'orderbook_1m') }}
    WHERE window_start >= toDateTime({{ day_from }}) AND window_start < toDateTime({{ day_to }} + INTERVAL 1 DAY)
)
SELECT
    -- FULL OUTER JOIN 에서 빠진 쪽은 NULL 이 아니라 기본값(0 → 1970-01-01)이라 coalesce 가 틀린다 → 건수로 판정
    toDate(if(t.trade_count > 0, t.minute, b.minute))   AS day_utc,
    if(t.trade_count > 0, t.market, b.market)           AS market,
    if(t.trade_count > 0, t.minute, b.minute)           AS minute,
    -- 체결
    t.trade_count AS trade_count, t.volume AS volume, t.amount AS amount, t.vwap AS vwap,
    t.open AS open, t.high AS high, t.low AS low, t.close AS close, t.buy_ratio AS buy_ratio,
    if(t.close > 0, (t.high - t.low) / t.close * 10000, NULL) AS price_range_bp,
    -- 호가
    b.snapshots AS snapshots, b.mid_open AS mid_open, b.mid_close AS mid_close, b.mid_min AS mid_min, b.mid_max AS mid_max,
    b.spread_bp_avg AS spread_bp_avg, b.spread_bp_max AS spread_bp_max,
    b.imb1_avg AS imb1_avg, b.imb5_avg AS imb5_avg, b.imb15_avg AS imb15_avg,
    b.ask_depth15_avg AS ask_depth15_avg, b.bid_depth15_avg AS bid_depth15_avg,
    -- 결합 파생
    if(b.ask_depth15_avg + b.bid_depth15_avg > 0, t.volume / (b.ask_depth15_avg + b.bid_depth15_avg), NULL) AS volume_over_depth15,
    if(b.mid_close > 0 AND t.close > 0, (t.close - b.mid_close) / b.mid_close * 10000, NULL)             AS close_vs_mid_bp,
    toUInt8(t.trade_count > 0) AS has_trades, toUInt8(b.snapshots > 0) AS has_book   -- LowCardinality 컬럼 비교는 LowCardinality(UInt8) 를 만들어 금지됨 → 일반 컬럼으로 판정
FROM trades AS t
FULL OUTER JOIN book AS b ON b.market = t.market AND b.minute = t.minute
{% if is_incremental() %}
{% endif %}
