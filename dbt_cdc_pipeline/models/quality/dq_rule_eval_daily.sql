{{ config(order_by='(day, rule)') }}
-- 규칙 검증 기반 (docs/16 §5, docs/22): 우리 규칙의 출력을 거래소 지정 이력(정답)과 매일 대조해 정밀도·재현율·선행 시간을 낸다.
-- 이 표가 규칙의 존재 이유다. "임계값이 왜 그 값인가" 에 대한 답이 매일 갱신되는 숫자로 남는다. 섀도 → 승격 판단은 여기서 한다.
-- 매칭 정의: 같은 마켓, 우리 지정(level 0→N 전이)과 거래소 지정(TRIGGER 시각)이 ±10분 안. 선행 시간 = 거래소 − 우리 (양수면 우리가 먼저).
WITH ours_price AS (
    SELECT market, event_time AS t, event_time - INTERVAL 10 MINUTE AS t_lo FROM {{ source('reference', 'market_alerts') }}
    WHERE alert_type = 'PRICE_24H' AND prev_level = 0 AND level > 0
),
ex_price AS (
    SELECT market, trigger_time_utc AS t FROM {{ source('reference', 'upbit_market_event_records') }} FINAL
    WHERE event_type = 'PRICE_FLUCTUATIONS'
),
price_match AS (
    SELECT o.market, o.t AS ours_t, e.t AS ex_t
    FROM ours_price o ASOF LEFT JOIN ex_price e ON o.market = e.market AND e.t >= o.t_lo
),
price_day AS (
    SELECT toDate(ours_t) AS day, 'PRICE_24H' AS rule,
           count() AS ours, countIf(ex_t IS NOT NULL AND ex_t <= ours_t + INTERVAL 10 MINUTE) AS matched,
           CAST(round(medianIf(dateDiff('second', ours_t, ex_t), ex_t IS NOT NULL AND ex_t <= ours_t + INTERVAL 10 MINUTE), 0) AS Nullable(Float64)) AS lead_median_s
    FROM price_match GROUP BY day
),
ex_price_day AS (
    SELECT toDate(t) AS day, count() AS exchange FROM ex_price GROUP BY day
),
ours_vol AS (
    SELECT market, day FROM {{ ref('int_volume_surge_daily') }} WHERE flagged
),
ex_vol AS (
    SELECT market, toDate(trigger_time_utc) - 1 AS day FROM {{ source('reference', 'upbit_market_event_records') }} FINAL
    WHERE event_type = 'TRADING_VOLUME_SOARING' AND toHour(trigger_time_utc) = 1
    GROUP BY market, day
),
vol_day AS (
    SELECT o.day, 'VOLUME_24H' AS rule, count() AS ours, countIf(e.market != '') AS matched, CAST(NULL AS Nullable(Float64)) AS lead_median_s
    FROM ours_vol o LEFT JOIN ex_vol e ON o.market = e.market AND o.day = e.day GROUP BY o.day
),
ex_vol_day AS (SELECT day, count() AS exchange FROM ex_vol GROUP BY day)
SELECT p.day, p.rule, p.ours, p.matched, x.exchange,
       round(if(p.ours > 0, p.matched / p.ours, 0), 3)      AS precision,
       round(if(x.exchange > 0, p.matched / x.exchange, 0), 3) AS recall,
       p.lead_median_s
FROM price_day p LEFT JOIN ex_price_day x USING day
UNION ALL
SELECT v.day, v.rule, v.ours, v.matched, x.exchange,
       round(if(v.ours > 0, v.matched / v.ours, 0), 3),
       round(if(x.exchange > 0, v.matched / x.exchange, 0), 3),
       v.lead_median_s
FROM vol_day v LEFT JOIN ex_vol_day x USING day
