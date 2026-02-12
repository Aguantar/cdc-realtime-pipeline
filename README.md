# 🚀 On-Premise Real-time CDC Pipeline

> **물리 서버 기반 실시간 변경 데이터 캡처 및 분석 플랫폼**

## 💡 이 프로젝트가 증명하는 것

| 질문 | 답 |
|------|-----|
| 왜 On-Premise인가? | 클라우드가 아닌 물리 서버에서 직접 구축/운영 경험 |
| 왜 CDC인가? | Kafka Connect 기반 입수 파이프라인 구축 능력 |
| 왜 24시간 운영인가? | 실제 프로덕션 환경의 장기 운영 이슈 해결 경험 |
| 왜 실시간인가? | 배치가 아닌 스트리밍 처리 능력 |

**결국 이 프로젝트는:**
- ✅ On-Premise 클러스터 **구축/운영** 경험
- ✅ 제한된 리소스에서 **최적화** 경험
- ✅ 실시간 파이프라인 **설계/구현** 능력
- ✅ 장애 대응 및 **모니터링** 체계 구축

을 보여주기 위한 프로젝트입니다.

---

## 📋 프로젝트 개요

### 목표
1. **MySQL → Kafka CDC 파이프라인** 구축 (Debezium)
2. **실시간 주식 체결 데이터** 수집 (한국투자증권 API)
3. **Flink 실시간 스트리밍** 처리 (집계, 이상 탐지)
4. **ClickHouse OLAP** 저장 및 실시간 쿼리
5. **Grafana 대시보드** + **외부 접근** (Cloudflare Tunnel)
6. **16GB 메모리 제약** 속 최적화
7. **24시간 운영** + 장기 데이터 축적

### 차별화 포인트

| 일반 프로젝트 | 이 프로젝트 |
|--------------|-------------|
| AWS/GCP 관리형 서비스 | **On-Premise 물리 서버 직접 구축** |
| 로컬에서 잠깐 테스트 | **24시간 상시 운영** |
| 시연할 때만 실행 | **면접관이 실시간 접속 가능** |
| 무제한 리소스 | **16GB 메모리에서 최적화** |
| 시뮬레이션 데이터만 | **실제 주식 체결 데이터 수집** |

---

## 🏗️ 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    On-Premise Real-time CDC Pipeline                             │
│                       (Mini PC Server - 24/7 운영)                               │
└─────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────┐
│  [Data Sources]                                                                  │
│                                                                                  │
│  ┌─────────────┐     ┌─────────────┐                                            │
│  │   MySQL     │     │  한국투자    │                                            │
│  │  (주문 DB)  │     │ 증권 API    │                                            │
│  │             │     │ (실시간 체결)│                                            │
│  └──────┬──────┘     └──────┬──────┘                                            │
│         │                   │                                                    │
│         ▼                   ▼                                                    │
│  ┌─────────────┐     ┌─────────────┐                                            │
│  │  Debezium   │     │  WebSocket  │                                            │
│  │  (CDC)      │     │  Producer   │                                            │
│  └──────┬──────┘     └──────┬──────┘                                            │
│         │                   │                                                    │
│         └─────────┬─────────┘                                                    │
│                   ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                     Kafka Cluster (3 brokers)                            │    │
│  │   ┌─────────┐    ┌─────────┐    ┌─────────┐                             │    │
│  │   │ Broker1 │    │ Broker2 │    │ Broker3 │   * 메모리: 512MB × 3       │    │
│  │   └─────────┘    └─────────┘    └─────────┘   * Replication Factor: 3   │    │
│  └──────────────────────────┬──────────────────────────────────────────────┘    │
│                             │                                                    │
│                             ▼                                                    │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                     Flink Cluster                                        │    │
│  │   ┌─────────────┐    ┌─────────────┐                                    │    │
│  │   │ JobManager  │    │ TaskManager │    * Checkpoint: RocksDB           │    │
│  │   │   (512MB)   │    │   (1GB)     │    * Parallelism: 2                │    │
│  │   └─────────────┘    └─────────────┘                                    │    │
│  └──────────────────────────┬──────────────────────────────────────────────┘    │
│                             │                                                    │
│              ┌──────────────┼──────────────┐                                    │
│              ▼              ▼              ▼                                    │
│       [Raw Sink]    [Aggregation]   [Anomaly Alert]                            │
│              │              │              │                                    │
│              └──────────────┼──────────────┘                                    │
│                             ▼                                                    │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                    │
│  │  ClickHouse  │────▶│   Grafana    │────▶│  Cloudflare  │                    │
│  │    (OLAP)    │     │  Dashboard   │     │   Tunnel     │                    │
│  │    (2GB)     │     │              │     │ (외부 접근)  │                    │
│  └──────────────┘     └──────────────┘     └──────────────┘                    │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────┐
│  [Resource Allocation - 16GB RAM]                                                │
│                                                                                  │
│  MySQL: 1GB │ Kafka×3: 1.5GB │ ZK: 256MB │ Flink: 1.5GB │ ClickHouse: 2GB      │
│  Grafana: 256MB │ Prometheus: 256MB │ 기타: 1GB │ 여유: ~8GB                    │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 🛠️ 기술 스택

| 컴포넌트 | 기술 | 버전 | 메모리 | 역할 |
|----------|------|------|--------|------|
| Source DB | MySQL | 8.0 | 1GB | 주문 데이터 (CDC 소스) |
| CDC | Debezium | 2.5 | 512MB | binlog 캡처 |
| 실시간 데이터 | 한국투자증권 API | - | - | 주식 실시간 체결 |
| Message Queue | Apache Kafka | 3.6 | 512MB × 3 | 이벤트 스트리밍 |
| Coordination | Zookeeper | 3.8 | 256MB | Kafka 클러스터 관리 |
| Stream Processing | Apache Flink | 1.18 | 1.5GB | 실시간 처리 |
| OLAP | ClickHouse | 24.1 | 2GB | 분석 쿼리 |
| Dashboard | Grafana | 10.x | 256MB | 시각화 |
| External Access | Cloudflare Tunnel | - | - | 외부 접근 |
| Monitoring | Prometheus + Kafka UI | - | 256MB | 모니터링 |

---

## 📅 구현 단계

### Phase 1: 인프라 구축 (2-3일)
- [ ] Docker Compose 구성 (메모리 최적화)
- [ ] MySQL 스키마 생성
- [ ] Kafka 3 broker 클러스터 구축
- [ ] 전체 컨테이너 정상 실행 확인

### Phase 2: CDC 파이프라인 (2-3일)
- [ ] Debezium MySQL Connector 설정
- [ ] CDC 이벤트 캡처 확인 (INSERT/UPDATE/DELETE)
- [ ] Kafka Topic 생성 및 메시지 확인

### Phase 3: Flink 스트리밍 (3-4일)
- [ ] Flink Job 개발 (Java)
- [ ] 실시간 집계 (5분 윈도우)
- [ ] 이상 탐지 로직
- [ ] ClickHouse Sink 구현

### Phase 4: ClickHouse OLAP (2일)
- [ ] 테이블 스키마 설계
- [ ] Materialized View 생성
- [ ] 분석 쿼리 최적화

### Phase 5: 대시보드 + 외부 접근 (2-3일)
- [ ] Grafana 대시보드 구성
- [ ] Cloudflare Tunnel 설정
- [ ] 외부 접속 테스트

### Phase 6: 실시간 데이터 수집 (2일)
- [ ] 한국투자증권 API 연동
- [ ] WebSocket Producer 개발
- [ ] 주식 체결 데이터 Kafka 전송

### Phase 7: 24시간 운영 (2-3일)
- [ ] 데이터 생성기 (시간대별 패턴)
- [ ] systemd 서비스 등록
- [ ] 디스크 관리 자동화

### Phase 8: 장애 복구 + 문서화 (2-3일)
- [ ] 장애 시나리오 테스트
- [ ] 모니터링 알림 설정
- [ ] 성능 측정 및 문서화

---

## 📊 목표 지표

| 지표 | 목표 |
|------|------|
| E2E Latency | < 5초 |
| Throughput | > 200 TPS |
| 데이터 정합성 | 100% |
| 장애 복구 시간 | < 1분 |
| 메모리 사용 | < 14GB |
| 24시간 운영 | ✅ |
| 외부 접근 | ✅ |

---

## 📁 프로젝트 구조

```
cdc-realtime-pipeline/
├── README.md
├── docker-compose.yml
├── .env.example
├── mysql/
│   └── init.sql
├── debezium/
│   └── connector-config.json
├── flink/
│   ├── pom.xml
│   └── src/main/java/
├── clickhouse/
│   ├── schema.sql
│   └── config.xml
├── grafana/
│   ├── dashboards/
│   └── datasources/
├── prometheus/
│   ├── prometheus.yml
│   └── alertmanager.yml
├── scripts/
│   ├── start.sh
│   ├── stop.sh
│   ├── data_generator.py
│   ├── stock_producer.py
│   ├── failure-test.sh
│   └── cleanup.sh
├── docs/
│   ├── 01-architecture.md
│   ├── 02-infrastructure.md
│   ├── 03-cdc-pipeline.md
│   ├── 04-flink-streaming.md
│   ├── 05-clickhouse-schema.md
│   ├── 06-dashboard.md
│   ├── 07-external-access.md
│   ├── 08-memory-tuning.md
│   ├── 09-monitoring.md
│   ├── 10-troubleshooting.md
│   └── 11-performance-report.md
└── tests/
    └── failure-scenarios/
```

---

## 🎤 면접 예상 질문

### Q1. 왜 On-Premise를 선택했나요?
> "토스 JD에 'On-Premise 클러스터 구축'이 명시되어 있어서, 클라우드 관리형 서비스가 아닌 물리 서버에서 직접 구축/운영 경험을 보여주고 싶었습니다."

### Q2. 16GB 메모리에서 어떻게 최적화했나요?
> "Kafka broker당 512MB, Flink TaskManager 1GB로 제한하고, 총 10개 이상의 컴포넌트를 8GB 이내로 운영했습니다. OOM 없이 안정적으로 24시간 운영 중입니다."

### Q3. 왜 Debezium을 선택했나요?
> "MySQL binlog를 직접 파싱하는 것보다 Debezium이 스키마 변경 추적, exactly-once 전달, Kafka Connect 통합을 제공해서 선택했습니다."

### Q4. Kafka 파티션 전략은?
> "order_id 기준 해싱으로 파티셔닝했습니다. 같은 주문의 이벤트가 순서대로 처리되어야 하기 때문입니다."

### Q5. 장애 상황에서 어떻게 대응했나요?
> "Kafka Broker 1대 다운 시뮬레이션에서 replication factor 3 설정으로 데이터 유실 없이 자동 failover됨을 확인했습니다."

### Q6. 실시간 주식 데이터를 왜 추가했나요?
> "CDC만으로는 시뮬레이션 데이터에 그치기 때문에, 실제 데이터를 수집하여 파이프라인의 현실성을 높이고, 두 가지 입수 경로(CDC + API)를 하나의 파이프라인에서 처리하는 경험을 쌓기 위함입니다."

---

## 🔗 관련 프로젝트

- [FDS Pipeline Lab](https://github.com/Aguantar/fds-pipeline-lab) - 이상거래 탐지 파이프라인 (TPS 250배 최적화)

---

## 📝 진행 상황

- [x] 프로젝트 계획 수립
- [ ] Phase 1: 인프라 구축
- [ ] Phase 2: CDC 파이프라인
- [ ] Phase 3: Flink 스트리밍
- [ ] Phase 4: ClickHouse OLAP
- [ ] Phase 5: 대시보드 + 외부 접근
- [ ] Phase 6: 실시간 데이터 수집
- [ ] Phase 7: 24시간 운영
- [ ] Phase 8: 장애 복구 + 문서화

---

## 🖥️ 서버 환경

| 항목 | 스펙 |
|------|------|
| 하드웨어 | Mini PC (On-Premise) |
| OS | Ubuntu 24.04 |
| CPU | 4코어 |
| RAM | 16GB |
| Disk | 500GB SSD |
| 운영 | 24시간 상시 |
