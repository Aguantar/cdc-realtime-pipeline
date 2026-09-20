#!/bin/bash
# 2026-09-20 (docs/34 #7): v1 규칙(anomaly_alerts)은 09-17 에 폐기됐는데 이 cron 이 매분 빈 결과를 돌고 있었다 → v2 등급 전이(market_alerts)로 교체.
docker exec cdc-clickhouse clickhouse-client --query "
SELECT toUnixTimestamp(detected_at)*1000 as ts, alert_type, market,
       concat(toString(prev_level), '->', toString(level), ' (', toString(round(value, 1)), '%, ', rule_version, ')') AS message
FROM cdc_pipeline.market_alerts
WHERE detected_at >= now() - INTERVAL 1 MINUTE
ORDER BY detected_at FORMAT JSONEachRow
" 2>/dev/null | while read -r line; do
  ts=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['ts'])")
  text=$(echo "$line" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['alert_type']} | {d['market']}: {d['message']}\")")
  tags=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['alert_type'])")
  
  curl -s -X POST http://localhost:3000/api/annotations \
    -H "Content-Type: application/json" \
    -u admin:cdc_grafana_2025 \
    -d "{\"dashboardId\": 3, \"panelId\": 12, \"time\": $ts, \"text\": \"$text\", \"tags\": [\"$tags\"]}" > /dev/null
done
