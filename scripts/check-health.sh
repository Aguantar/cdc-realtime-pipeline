#!/bin/bash
echo "============================================"
echo "  CDC Pipeline Health Check"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================"
echo ""

echo "[1] System Memory"
free -h | head -2
echo ""

echo "[2] Container Status"
docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null
echo ""

echo "[3] Container Memory Usage"
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" | grep cdc-
echo ""

echo "[4] Kafka Cluster - Broker Count"
BROKER_COUNT=$(docker exec cdc-kafka-1 kafka-broker-api-versions --bootstrap-server kafka-1:29092 2>/dev/null | grep "id:" | wc -l)
echo "Active brokers: $BROKER_COUNT / 3"
echo ""

echo "[5] Kafka - Topic List"
docker exec cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 --list 2>/dev/null
echo ""

echo "[6] MySQL - Ping"
docker exec cdc-mysql mysqladmin -u root -pcdc_root_2025 ping 2>/dev/null
echo ""

echo "============================================"
echo "  Health Check Complete"
echo "============================================"
