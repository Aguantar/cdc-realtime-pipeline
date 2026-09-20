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
iptables -C DOCKER-USER -p tcp -m multiport --dports $PORTS -m conntrack --ctorigdstport $PORTS -j DROP 2>/dev/null || iptables -I DOCKER-USER 4 -p tcp -m multiport --dports $PORTS -m conntrack --ctorigdstport $PORTS -j DROP
iptables -L DOCKER-USER -n --line-numbers | head -8
HOST_IP=$(hostname -I | awk '{print $1}')
echo
echo "확인 (LAN 의 다른 기기에서):"
echo "  막혀야 함:  for p in 9092 2181 8081 6379 5432; do nc -vz -w3 $HOST_IP \$p; done   → 전부 timeout"
echo "  살아야 함:  nc -vz -w3 $HOST_IP 3000 ; nc -vz -w3 $HOST_IP 8085                  → succeeded"
echo
echo "재부팅하면 규칙이 사라진다. 영구화:  sudo apt install iptables-persistent  (또는 부팅 시 이 스크립트 실행)"
