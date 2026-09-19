#!/usr/bin/env bash
# 토픽 정의를 코드로 (docs/29 §4·§6). 지금과 KRaft 컷오버 때 같은 스크립트를 돌린다. 존재하면 설정만 맞추고, 없으면 만든다.
# 왜 zstd: 800B JSON 을 그대로 쌓아 12.4M 건 9.97GB(docs/29 §1). 왜 7일 시간 보존: 재처리엔 "며칠치가 있다"는 약속이 필요한데 bytes 상한은 날짜를 보장 못 한다.
set -euo pipefail
K="docker exec cdc-kafka-1"; BS="--bootstrap-server kafka-1:29092"
declare -A TOPICS=(
  ["cdc.crypto_db.crypto_trades"]="partitions=3 compression.type=zstd retention.ms=604800000 retention.bytes=8589934592 min.insync.replicas=1"
  ["upbit.orderbook.v1"]="partitions=6 compression.type=producer retention.ms=86400000 retention.bytes=6442450944 min.insync.replicas=1"
  ["cdc.dlq.crypto_trades"]="partitions=1 compression.type=zstd retention.ms=2592000000 min.insync.replicas=1"
)
for t in "${!TOPICS[@]}"; do
  spec="${TOPICS[$t]}"; parts=$(echo "$spec" | grep -oE "partitions=[0-9]+" | cut -d= -f2); cfg=$(echo "$spec" | sed 's/partitions=[0-9]* //' | tr ' ' ',')
  if $K kafka-topics $BS --describe --topic "$t" >/dev/null 2>&1; then
    $K kafka-configs $BS --entity-type topics --entity-name "$t" --alter --add-config "$cfg" >/dev/null && echo "updated $t: $cfg"
  else
    $K kafka-topics $BS --create --topic "$t" --partitions "$parts" --replication-factor 1 --config "$(echo $cfg | sed 's/,/ --config /g')" >/dev/null && echo "created $t ($parts p): $cfg"
  fi
done
for t in "${!TOPICS[@]}"; do echo -n "$t → "; $K kafka-configs $BS --entity-type topics --entity-name "$t" --describe 2>/dev/null | grep -oE "(compression.type|retention.ms|retention.bytes)=[^ ,]+" | tr '\n' ' '; echo; done
