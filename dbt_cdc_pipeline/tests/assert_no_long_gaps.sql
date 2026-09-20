-- 최근 24시간 내 연속 3시간 이상 데이터 누락 체크
-- 코인은 24시간 거래이므로 장시간 공백은 파이프라인 장애 의심
-- 첫 행(이전 데이터 없음)은 제외
-- 검사 범위: int_ohlcv_1h에 실제 존재하는 마지막 시각까지 (dbt run 이후 미집계 구간 제외)
WITH max_hour AS (
    SELECT max(hour_kst) AS last_hour
    FROM {{ ref('int_ohlcv_1h') }}
),
hourly_exists AS (
    SELECT
        market,
        hour_kst,
        dateDiff('hour', lagInFrame(hour_kst) OVER (
            PARTITION BY market ORDER BY hour_kst
        ), hour_kst) AS gap_hours
    FROM {{ ref('int_ohlcv_1h') }}
    WHERE hour_kst >= (SELECT last_hour FROM max_hour) - INTERVAL 24 HOUR
      AND hour_kst <= (SELECT last_hour FROM max_hour)
)
SELECT *
FROM hourly_exists
WHERE gap_hours >= 3
  AND gap_hours < 10000
