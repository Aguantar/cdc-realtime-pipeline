#!/usr/bin/env bash
# LAN → 프로덕션 관리 포트 차단 (docs/29 §5). 도커가 퍼블리시한 포트는 INPUT 이 아니라 DOCKER-USER 체인(FORWARD)을 지난다.
# 허용: 호스트 자신(lo), 도커 브리지(172.16/12), WireGuard(10.88.0.0/24). 차단: 그 외에서 오는 아래 PORTS.
#
# 2026-09-20 점검에서 목록을 늘렸다. 실제로 0.0.0.0 에 열린 것을 `ss -ltnp` 로 세어 보니
# 이 저장소 밖 컨테이너 둘이 더 있었다:
#   6379 fds-redis  — `redis-server --appendonly yes` 뿐, **requirepass 가 없다**. LAN 의 누구나 읽고 쓸 수 있고
#                     CONFIG SET 으로 파일을 쓰는 알려진 경로가 있다. 이 목록에서 가장 위험한 항목.
#   5432 my-postgres — POSTGRES_PASSWORD 는 있지만 DB 포트를 LAN 에 둘 이유가 없다.
# 둘 다 이 프로젝트 것이 아니지만 같은 미니PC 이고, 뚫리면 같은 호스트다. 그래서 같이 막는다.
#
# 일부러 열어 두는 것: 22(SSH), 3000(Grafana, 인증 있음), 8085(Airflow UI, 인증 있음),
#   8089(circuit-connect-api, 앱 자체 인증). 사용자가 LAN 에서 쓰는 화면들이다.
# WireGuard(10.88.0.0/24)는 위에서 RETURN 이므로 Oracle 쪽 접근은 그대로 산다.
# 실행: sudo scripts/ops/lan-firewall.sh   (재부팅 후엔 다시 실행 — 영구화는 iptables-persistent 로, 다음 재기동 창에 compose 바인딩으로 대체)
set -euo pipefail
PORTS="9092,2181,8083,8081,8123,6379,5432"
iptables -C DOCKER-USER -s 172.16.0.0/12 -j RETURN 2>/dev/null || iptables -I DOCKER-USER 1 -s 172.16.0.0/12 -j RETURN
iptables -C DOCKER-USER -s 10.88.0.0/24 -j RETURN 2>/dev/null || iptables -I DOCKER-USER 2 -s 10.88.0.0/24 -j RETURN
iptables -C DOCKER-USER -s 127.0.0.0/8 -j RETURN 2>/dev/null || iptables -I DOCKER-USER 3 -s 127.0.0.0/8 -j RETURN
# 2026-09-20 수정: 처음엔 multiport + `--ctorigdstport $PORTS` 한 줄이었는데 실행하면 이렇게 죽는다.
#   iptables v1.8.10 (nf_tables): Port "9092,2181,..." does not resolve to anything.
# conntrack 의 --ctorigdstport 는 **포트 하나(또는 a:b 범위)만** 받는다. 쉼표 목록을 못 받는다.
# 스크립트를 한 번도 실행해 본 적이 없어서 여태 안 드러났다 — 안 돌려 본 런북은 런북이 아니다.
# → 포트마다 한 줄씩 넣는다. --ctorigdstport 를 쓰는 이유는 도커가 DNAT 를 먼저 하기 때문이다.
#   FORWARD(=DOCKER-USER)에 도달할 때 목적지 포트는 이미 컨테이너 포트라, 우리가 막으려는 '퍼블리시된
#   호스트 포트'로 판단하려면 conntrack 이 기억하는 **원래 목적지 포트**를 봐야 한다.
# -A(끝에 추가)가 아니라 -I 4(4번째에 삽입)인 이유: DOCKER-USER 체인의 **마지막 줄은 도커가 넣은 RETURN**이다.
# 끝에 붙이면 RETURN 뒤라 절대 평가되지 않는다 — 규칙이 들어간 것처럼 보이고 아무것도 안 막는다.
# 위에서 허용(RETURN) 3줄을 1·2·3 에 넣었으므로 그다음 자리가 4 다. DROP 들끼리는 포트가 겹치지 않아 순서 무관.
for p in ${PORTS//,/ }; do
  iptables -C DOCKER-USER -p tcp --dport "$p" -m conntrack --ctorigdstport "$p" -j DROP 2>/dev/null \
    || iptables -I DOCKER-USER 4 -p tcp --dport "$p" -m conntrack --ctorigdstport "$p" -j DROP
done
echo "현재 DOCKER-USER 규칙:"
iptables -L DOCKER-USER -n --line-numbers
echo
N_DROP=$(iptables -S DOCKER-USER | grep -c ctorigdstport); N_WANT=$(echo "$PORTS" | tr ',' '\n' | wc -l)
echo "DROP 규칙 ${N_DROP}개 / 기대 ${N_WANT}개"
# 규칙이 RETURN 뒤에 있으면 들어가 있어도 아무것도 안 막는다 → 자리까지 확인한다
LAST_DROP=$(iptables -L DOCKER-USER -n --line-numbers | awk '/ctorigdstport|dpt:/ {n=$1} END {print n+0}')
FINAL_RETURN=$(iptables -L DOCKER-USER -n --line-numbers | awk '$2=="RETURN" && $5=="0.0.0.0/0" {n=$1} END {print n+0}')
if [ "$N_DROP" -eq "$N_WANT" ] && { [ "$FINAL_RETURN" -eq 0 ] || [ "$LAST_DROP" -lt "$FINAL_RETURN" ]; }; then
  echo "OK: DROP 규칙이 전부 들어갔고 마지막 RETURN 앞에 있다"
else
  echo "경고: 규칙 수 또는 순서 확인 필요 (DROP 마지막줄 $LAST_DROP, RETURN $FINAL_RETURN)"
fi
HOST_IP=$(hostname -I | awk '{print $1}')
echo
echo "확인 (LAN 의 다른 기기에서):"
echo "  막혀야 함:  for p in 9092 2181 8081 6379 5432; do nc -vz -w3 $HOST_IP \$p; done   → 전부 timeout"
echo "  살아야 함:  nc -vz -w3 $HOST_IP 3000 ; nc -vz -w3 $HOST_IP 8085                  → succeeded"
echo
echo "재부팅하면 규칙이 사라진다. 영구화:  sudo apt install iptables-persistent  (또는 부팅 시 이 스크립트 실행)"
