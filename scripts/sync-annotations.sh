#!/bin/bash
docker exec cdc-clickhouse clickhouse-client --query "
SELECT toUnixTimestamp(detected_at)*1000 as ts, alert_type, market, message 
FROM cdc_pipeline.anomaly_alerts 
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
