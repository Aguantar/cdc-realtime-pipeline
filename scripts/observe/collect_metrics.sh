#!/bin/bash
# ============================================================
#  7일 관찰용 파이프라인 지표 스냅샷 (5분 간격, crontab)
#  - 읽기 전용: docker logs/stats, Flink REST, Kafka CLI, ClickHouse readonly_user, MySQL COUNT
#  - 출력: $OUT_DIR/metrics_5m.csv (헤더 1회), 실패 항목은 빈 값
#  - 등록: */5 * * * * /home/calme/cdc-realtime-pipeline/scripts/observe/collect_metrics.sh
#  근거: docs/worklog.md 결정 09-09 04:28 (7일 무변경 관찰, cron 수집)
# ============================================================
set -u
OUT_DIR="${OUT_DIR:-/home/calme/pipeline-observation}"
CSV="$OUT_DIR/metrics_5m.csv"
ENV_FILE=/home/calme/cdc-realtime-pipeline/.env
mkdir -p "$OUT_DIR"
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# --- ClickHouse (readonly_user, HTTP) ---
CH_USER=$(grep '^CLICKHOUSE_READONLY_USER=' $ENV_FILE | cut -d= -f2-)
CH_PASS=$(grep '^CLICKHOUSE_READONLY_PASSWORD=' $ENV_FILE | cut -d= -f2-)
chq() { curl -s --max-time 60 "http://localhost:8123/?user=$CH_USER&password=$CH_PASS&max_memory_usage=500000000&max_threads=2" --data-binary "$1" 2>/dev/null | tr '\t' ',' | tr -d '\n'; }

# 체결: 최근 5분 행수·마켓수·ingest lag(source_ts−upbit_ts) p50/p95/max·cdc_latency·flink lag
TRADE=$(chq "SELECT count(), uniqExact(market), round(quantile(0.5)(toUnixTimestamp64Milli(source_ts)-upbit_timestamp)/1000,2), round(quantile(0.95)(toUnixTimestamp64Milli(source_ts)-upbit_timestamp)/1000,2), round(max(toUnixTimestamp64Milli(source_ts)-upbit_timestamp)/1000,2), round(avg(cdc_latency_ms),1), round(quantile(0.95)(toUnixTimestamp64Milli(flink_ts)-toUnixTimestamp64Milli(source_ts))/1000,2), countIf(best_ask_price IS NULL) FROM cdc_pipeline.crypto_trades WHERE source_ts >= now() - INTERVAL 5 MINUTE FORMAT TSV")
# 호가: 최근 5분 행수·마켓수·recv lag p50/p95·e2e p50/p95/max
OB=$(chq "SELECT count(), uniqExact(market), round(quantile(0.5)(toUnixTimestamp64Milli(recv_ts)-toUnixTimestamp64Milli(ts))), round(quantile(0.95)(toUnixTimestamp64Milli(recv_ts)-toUnixTimestamp64Milli(ts))), round(quantile(0.5)(toUnixTimestamp64Milli(flink_ts)-toUnixTimestamp64Milli(ts))), round(quantile(0.95)(toUnixTimestamp64Milli(flink_ts)-toUnixTimestamp64Milli(ts))), round(max(toUnixTimestamp64Milli(flink_ts)-toUnixTimestamp64Milli(ts))) FROM cdc_pipeline.orderbook_raw WHERE ts >= now() - INTERVAL 5 MINUTE FORMAT TSV")
OB1M=$(chq "SELECT count(), uniqExact(market) FROM cdc_pipeline.orderbook_1m WHERE window_start >= now() - INTERVAL 5 MINUTE FORMAT TSV")
ALERTS=$(chq "SELECT count(), countIf(alert_type='LARGE_TRADE'), countIf(alert_type='PRICE_SPIKE'), countIf(alert_type='VOLUME_SURGE') FROM cdc_pipeline.anomaly_alerts WHERE detected_at >= now() - INTERVAL 5 MINUTE FORMAT TSV")
# system.* 은 readonly_user 권한 밖 → 컨테이너 내부 clickhouse-client(조회 전용)로
CHMEM=$(docker exec cdc-clickhouse clickhouse-client --max_threads=1 -q "SELECT value FROM system.metrics WHERE metric='MemoryTracking'" 2>/dev/null | tr -d '\n')
CHPARTS=$(docker exec cdc-clickhouse clickhouse-client --max_threads=1 -q "SELECT countIf(table='crypto_trades'), countIf(table='orderbook_raw'), sum(bytes_on_disk) FROM system.parts WHERE active AND database='cdc_pipeline'" 2>/dev/null | tr '\t' ',' | tr -d '\n')
# 어떤 값이든 콤마/개행이 섞이면 CSV가 깨지므로 방어
CHMEM=${CHMEM//,/}; TRADE=${TRADE//$'\n'/}; OB=${OB//$'\n'/}

# --- producer / collector 마지막 STATS ---
P=$(docker logs cdc-upbit-producer --tail 40 2>&1 | grep '\[STATS\]' | tail -1)
P_RECV=$(echo "$P" | sed -n 's/.*received=\([0-9]*\).*/\1/p'); P_INS=$(echo "$P" | sed -n 's/.*inserted=\([0-9]*\).*/\1/p'); P_DUP=$(echo "$P" | sed -n 's/.*duplicates=\([0-9]*\).*/\1/p'); P_ERR=$(echo "$P" | sed -n 's/.*errors=\([0-9]*\).*/\1/p'); P_BUF=$(echo "$P" | sed -n 's/.*buffer=\([0-9]*\).*/\1/p')
P_WARN=$(docker logs cdc-upbit-producer --since 5m 2>&1 | grep -c -E 'WARNING|ERROR')
C=$(docker logs cdc-orderbook-collector --tail 40 2>&1 | grep '\[STATS\]' | tail -1)
C_RECV=$(echo "$C" | sed -n 's/.*recv=\([0-9]*\).*/\1/p'); C_DERR=$(echo "$C" | sed -n 's/.*deliv_err=\([0-9]*\).*/\1/p'); C_Q=$(echo "$C" | sed -n 's/.*queue=\([0-9]*\).*/\1/p'); C_RATE=$(echo "$C" | sed -n 's/.*rate=\([0-9.]*\).*/\1/p'); C_P95=$(echo "$C" | sed -n 's/.*lag_p95=\([0-9]*\).*/\1/p'); C_RECON=$(echo "$C" | sed -n 's/.*reconnects=\([0-9]*\).*/\1/p')

# --- Flink ---
FL=$(curl -s --max-time 10 localhost:8081/jobs/overview | python3 -c '
import json,sys,urllib.request
d=json.load(sys.stdin); out=[]
for name in ("CDC Realtime Pipeline","Orderbook Pipeline","Circuit Connect Stream Processing"):
    js=[j for j in d["jobs"] if j["name"]==name and j["state"]=="RUNNING"]
    if not js: out+=["0","","","",""]; continue
    j=js[0]
    try:
        c=json.load(urllib.request.urlopen("http://localhost:8081/jobs/%s/checkpoints"%j["jid"],timeout=10))
        h=c["history"][0] if c["history"] else {}
        out+=["1",str(c["counts"]["completed"]),str(c["counts"]["failed"]),str(h.get("state_size","")),str(h.get("end_to_end_duration",""))]
    except Exception: out+=["1","","","",""]
print(",".join(out))' 2>/dev/null)
TMID=$(curl -s --max-time 10 localhost:8081/taskmanagers | python3 -c 'import json,sys;print(json.load(sys.stdin)["taskmanagers"][0]["id"])' 2>/dev/null)
TMM=$(curl -s --max-time 10 "localhost:8081/taskmanagers/$TMID/metrics?get=Status.JVM.Memory.Heap.Used,Status.JVM.Memory.Metaspace.Used" | python3 -c 'import json,sys;d={m["id"]:m["value"] for m in json.load(sys.stdin)};print(d.get("Status.JVM.Memory.Heap.Used",""),d.get("Status.JVM.Memory.Metaspace.Used",""),sep=",")' 2>/dev/null)

# --- Kafka ---
K_TRADE=$(docker exec cdc-kafka-1 kafka-get-offsets --bootstrap-server kafka-1:29092 --topic cdc.crypto_db.crypto_trades 2>/dev/null | awk -F: '{s+=$3} END {print s}')
K_OB=$(docker exec cdc-kafka-1 kafka-get-offsets --bootstrap-server kafka-1:29092 --topic upbit.orderbook.v1 2>/dev/null | awk -F: '{s+=$3} END {print s}')
K_LAG_CDC=$(docker exec cdc-kafka-1 kafka-consumer-groups --bootstrap-server kafka-1:29092 --describe --group flink-cdc-consumer 2>/dev/null | grep crypto_trades | awk '{s+=$6} END {print s}')
K_LAG_OB=$(docker exec cdc-kafka-1 kafka-consumer-groups --bootstrap-server kafka-1:29092 --describe --group flink-orderbook-consumer 2>/dev/null | grep orderbook | awk '{s+=$6} END {print s}')
K_DISK=$(docker exec cdc-kafka-1 sh -c 'du -sb /var/lib/kafka/data 2>/dev/null | cut -f1')

# --- MySQL ---
MY=$(docker exec cdc-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*), MAX(trade_id) FROM crypto_db.crypto_trades" 2>/dev/null' | tr '\t' ',')

# --- 컨테이너/호스트 ---
DS=$(docker stats --no-stream --format '{{.Name}},{{.MemUsage}},{{.CPUPerc}}' cdc-clickhouse cdc-flink-taskmanager cdc-kafka-1 cdc-kafka-2 cdc-kafka-3 cdc-mysql cdc-upbit-producer cdc-orderbook-collector cdc-kafka-connect 2>/dev/null | awk -F, '{split($2,a," / "); printf "%s,%s,", a[1], $3}' | sed 's/,$//')
LOAD=$(cut -d' ' -f1-3 /proc/loadavg | tr ' ' ',')
MEM=$(free -m | awk 'NR==2{printf "%s,%s,", $3, $7} NR==3{printf "%s", $3}')
DF=$(df -B1 / | awk 'NR==2{print $3}')

HEADER="ts,tr_rows5m,tr_markets,tr_lag_p50_s,tr_lag_p95_s,tr_lag_max_s,tr_cdc_lat_ms,tr_flink_lag_p95_s,tr_best_null,ob_rows5m,ob_markets,ob_recv_p50_ms,ob_recv_p95_ms,ob_e2e_p50_ms,ob_e2e_p95_ms,ob_e2e_max_ms,ob1m_rows5m,ob1m_markets,alerts5m,alerts_large,alerts_spike,alerts_surge,ch_mem_bytes,ch_parts_trades,ch_parts_ob,ch_bytes_cdc_pipeline,p_received,p_inserted,p_dups,p_errors,p_buffer,p_warn5m,c_recv,c_deliv_err,c_queue,c_rate,c_lag_p95_ms,c_reconnects,fl_cdc_run,fl_cdc_cp_ok,fl_cdc_cp_fail,fl_cdc_state,fl_cdc_e2e,fl_ob_run,fl_ob_cp_ok,fl_ob_cp_fail,fl_ob_state,fl_ob_e2e,fl_cc_run,fl_cc_cp_ok,fl_cc_cp_fail,fl_cc_state,fl_cc_e2e,tm_heap_used,tm_metaspace_used,k_trade_endoffset,k_ob_endoffset,k_lag_cdc,k_lag_ob,k_disk_b1_bytes,my_rows,my_max_id,ch_mem,ch_cpu,tm_mem,tm_cpu,k1_mem,k1_cpu,k2_mem,k2_cpu,k3_mem,k3_cpu,my_mem,my_cpu,p_mem,p_cpu,c_mem,c_cpu,conn_mem,conn_cpu,load1,load5,load15,host_used_mb,host_avail_mb,swap_used_mb,df_used_bytes"
[ -f "$CSV" ] || echo "$HEADER" > "$CSV"
echo "$TS,$TRADE,$OB,$OB1M,$ALERTS,$CHMEM,$CHPARTS,$P_RECV,$P_INS,$P_DUP,$P_ERR,$P_BUF,$P_WARN,$C_RECV,$C_DERR,$C_Q,$C_RATE,$C_P95,$C_RECON,$FL,$TMM,$K_TRADE,$K_OB,$K_LAG_CDC,$K_LAG_OB,$K_DISK,$MY,$DS,$LOAD,$MEM,$DF" >> "$CSV"
