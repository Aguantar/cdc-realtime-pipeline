{{
    config(
        materialized='table',
        order_by='hour, alert_type, market'
    )
}}

-- Flink 이상 탐지 알림 발생률 집계
-- 운영 KPI: 룰별/마켓별 알림 빈도 → Grafana 히트맵 연동
-- ※ 데이터 볼륨이 충분할 때만 의미 있음
SELECT
    toStartOfHour(detected_at) AS hour,
    alert_type,
    market,
    count(*) AS alert_count,
    -- 시간대별 알림 분포 파악용
    round(avg(value), 2) AS avg_value,
    round(avg(threshold), 2) AS avg_threshold
FROM {{ source('raw', 'anomaly_alerts') }}
GROUP BY hour, alert_type, market
ORDER BY hour DESC
