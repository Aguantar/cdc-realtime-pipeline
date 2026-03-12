-- 최근 24시간 내 연속 3시간 이상 데이터 누락 체크
-- 코인은 24시간 거래이므로 장시간 공백은 파이프라인 장애 의심
-- 첫 행(이전 데이터 없음)은 제외
WITH hourly_exists AS (
    SELECT
        market,
        hour_start,
        dateDiff('hour', lagInFrame(hour_start) OVER (
            PARTITION BY market ORDER BY hour_start
        ), hour_start) AS gap_hours
    FROM {{ ref('int_ohlcv_1h') }}
    WHERE hour_start >= now() - INTERVAL 24 HOUR
)
SELECT *
FROM hourly_exists
WHERE gap_hours >= 3
  AND gap_hours < 10000
