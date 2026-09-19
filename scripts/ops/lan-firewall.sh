#!/usr/bin/env bash
# LAN → 프로덕션 관리 포트 차단 (docs/29 §5). 도커가 퍼블리시한 포트는 INPUT 이 아니라 DOCKER-USER 체인(FORWARD)을 지난다.
# 허용: 호스트 자신(lo), 도커 브리지(172.16/12), WireGuard(10.88.0.0/24). 차단: 그 외에서 오는 9092·2181·8083·8081 (8123 은 n8n 때문에 wg0 허용됨).
# 실행: sudo scripts/ops/lan-firewall.sh   (재부팅 후엔 다시 실행 — 영구화는 iptables-persistent 로, 다음 재기동 창에 compose 바인딩으로 대체)
set -euo pipefail
PORTS="9092,2181,8083,8081,8123"
iptables -C DOCKER-USER -s 172.16.0.0/12 -j RETURN 2>/dev/null || iptables -I DOCKER-USER 1 -s 172.16.0.0/12 -j RETURN
iptables -C DOCKER-USER -s 10.88.0.0/24 -j RETURN 2>/dev/null || iptables -I DOCKER-USER 2 -s 10.88.0.0/24 -j RETURN
iptables -C DOCKER-USER -s 127.0.0.0/8 -j RETURN 2>/dev/null || iptables -I DOCKER-USER 3 -s 127.0.0.0/8 -j RETURN
iptables -C DOCKER-USER -p tcp -m multiport --dports $PORTS -m conntrack --ctorigdstport $PORTS -j DROP 2>/dev/null || iptables -I DOCKER-USER 4 -p tcp -m multiport --dports $PORTS -m conntrack --ctorigdstport $PORTS -j DROP
iptables -L DOCKER-USER -n --line-numbers | head -8
echo "verify from another LAN device: nc -vz $(hostname -I | awk '{print $1}') 9092  → should time out"
