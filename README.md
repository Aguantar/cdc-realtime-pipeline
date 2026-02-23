# 🚀 On-Premise Real-time CDC Pipeline

> **물리 서버 기반 암호화폐 실시간 변경 데이터 캡처 및 이상 탐지 플랫폼**
>
> 🔗 **Live Dashboard**: [grafana.calmee.store](https://grafana.calmee.store)

## 💡 이 프로젝트가 증명하는 것

| 질문 | 답 |
|------|-----|
| 왜 On-Premise인가? | 클라우드가 아닌 물리 서버에서 직접 구축/운영 경험 |
| 왜 CDC인가? | Debezium + Kafka Connect 기반 실시간 입수 파이프라인 구축 능력 |
| 왜 24시간 운영인가? | 실제 프로덕션 환경의 장기 운영 이슈(장애 복구, 용량 관리) 해결 경험 |
| 왜 실시간인가? | 배치가 아닌 Flink 스트리밍 처리 + 이상 탐지 능력 |

**결국 이 프로젝트는:**
- ✅ On-Premise 클러스터 **구축/운영** 경험
- ✅ 제한된 리소스(16GB)에서 **최적화** 경험
- ✅ 실시간 CDC 파이프라인 **설계/구현** 능력
- ✅ 장애 대응 및 **모니터링** 체계 구축
- ✅ 도메인 기반 **이상 탐지** 룰 엔진 설계

을 보여주기 위한 프로젝트입니다.

---

## 📊 실시간 모니터링 대시보드

> **🔗 Live Dashboard**: [grafana.calmee.store](https://grafana.calmee.store) — 면접관이 직접 실시간 접속 가능

### 대시보드 패널 구성 (12개)

**상단 KPI (6개)**

| 패널 | 설명 |
|------|------|
| **Total Trades** | 파이프라인 가동 후 총 체결 건수 (현재 667만+) |
| **Avg CDC Latency** | 최근 1시간 평균 CDC 지연시간 (목표: <10ms, 실측: ~3ms) |
| **Active Alerts** | 최근 1시간 이상 탐지 알림 건수 |
| **Total Volume (KRW)** | 최근 1시간 총 체결 금액 (₩ 단위 자동 포맷) |
| **Markets Tracked** | 모니터링 중인 마켓 수 (BTC, ETH, XRP, SOL, DOGE) |
| **Pipeline Status** | 파이프라인 실시간 상태 (🟢 LIVE / 🔴 STALE) |

**시계열 차트 (4개)**

| 패널 | 설명 |
|------|------|
| **Trade Volume by Market (5min)** | 마켓별 5분 윈도우 총 거래금액 (Flink 집계) |
| **CDC Latency (ms)** | 분 단위 평균(녹색)/최대(주황) 레이턴시 추이 |
| **BTC Price (Realtime)** | BTC 분 단위 평균/최저/최고 + **이상 탐지 빨간 마커** |
| **Bid vs Ask by Market** | 마켓별 매수/매도 건수 비교 (매수압력 시각화) |

**데이터 테이블 (2개)**

| 패널 | 설명 |
|------|------|
| **Anomaly Alerts** | 이상 탐지 이력 (시간, 유형, 마켓, 상세 메시지) |
| **Recent Trades (Live)** | 최근 30건 실시간 체결 내역 (₩ 포맷, 레이턴시 표시) |

### 이상 탐지 규칙 (4가지)

> 임계값 근거: **업비트 이상거래 감시정책** (가상자산이용자보호법 7가지 유형) + **학술 논문** (arXiv:2503.08692, EWMA 기반 동적 임계값)

| 규칙 | 임계값 | 업비트 대응 유형 | 설명 |
|------|--------|----------------|------|
| **LARGE_TRADE** | BTC ₩5억, ETH ₩3억, 기타 ₩1억 | 체결관여 과다 | 단일 체결 금액 기준 대량 거래 |
| **PRICE_SPIKE** | BTC 2%, 기타 3% | 시세관여 과다 | 직전 체결 대비 급격한 가격 변동 |
| **VOLUME_SURGE** | EMA×50배 (최소 50건 학습) | 체결관여 과다 | 지수이동평균 대비 거래량 급증 |
| **RAPID_TRADES** | 100건/10초 | 특정종목 매매집중 | 단기간 동일 마켓 다수 체결 |

---

## 📋 프로젝트 개요

### 데이터 소스
- **Upbit WebSocket API**: 5개 암호화폐 마켓(KRW-BTC, KRW-ETH, KRW-XRP, KRW-SOL, KRW-DOGE) 실시간 체결 데이터
- 초당 ~8건, 일 ~580,000건 수집

### 파이프라인 흐름
```
Upbit WebSocket → MySQL → Debezium CDC → Kafka (3-broker) → Flink → ClickHouse → Grafana
```

### 차별화 포인트

| 일반 프로젝트 | 이 프로젝트 |
|--------------|-------------|
| AWS/GCP 관리형 서비스 | **On-Premise 물리 서버 직접 구축** |
| 로컬에서 잠깐 테스트 | **24시간 상시 운영 (10일+ 가동)** |
| 시연할 때만 실행 | **면접관이 실시간 접속 가능** (grafana.calmee.store) |
| 무제한 리소스 | **16GB 메모리에서 12개 컨테이너 최적화** |
| 시뮬레이션 데이터 | **Upbit 실시간 체결 데이터 (667만건+)** |
| 고정 임계값 이상 탐지 | **업비트 정책 + 학술 논문 기반 동적 임계값** |

---

## 🏗️ 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 On-Premise Real-time CDC Pipeline                           │
│                    (Mini PC Server - 24/7 운영)                              │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌─────────────┐
  │   Upbit     │
  │ WebSocket   │  실시간 체결 (5 마켓)
  │   API       │  ~8 TPS
  └──────┬──────┘
         ▼
  ┌─────────────┐     ┌──────────────┐
  │  WebSocket  │────▶│    MySQL     │  binlog 활성화
  │  Producer   │     │  (Source DB) │  7일 보존 + 매시간 cleanup
  └─────────────┘     └──────┬───────┘
                             │ CDC (binlog)
                             ▼
                      ┌──────────────┐
                      │   Debezium   │  MySQL CDC Connector
                      │   Connect    │  스냅샷 + 실시간 캡처
                      └──────┬───────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                  Kafka Cluster (3 Brokers)                    │
  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
  │  │ Broker 1 │  │ Broker 2 │  │ Broker 3 │  RF=3, 72h 보존  │
  │  │  512MB   │  │  512MB   │  │  512MB   │                   │
  │  └──────────┘  └──────────┘  └──────────┘                   │
  └──────────────────────┬───────────────────────────────────────┘
                         │
                         ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                   Flink Cluster                               │
  │  ┌─────────────┐  ┌──────────────┐                           │
  │  │ JobManager  │  │ TaskManager  │  Parallelism: 2           │
  │  │   512MB     │  │    1GB       │  Checkpoint: 60s          │
  │  └─────────────┘  └──────────────┘  Restart: 3x/10s         │
  │                                                               │
  │  ┌─────────────────────────────────────────────────┐         │
  │  │              Flink DataStream Job                │         │
  │  │                                                  │         │
  │  │  KafkaSource → NullSafeSchema → CdcEventParser  │         │
  │  │       │              │              │            │         │
  │  │       ▼              ▼              ▼            │         │
  │  │  [Raw Sink]   [5min Aggregation]  [Anomaly      │         │
  │  │                                    Detector]     │         │
  │  └─────────────────────────────────────────────────┘         │
  └──────────┬──────────────┬──────────────┬─────────────────────┘
             │              │              │
             ▼              ▼              ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                    ClickHouse (OLAP)                          │
  │                                                               │
  │  crypto_trades (원본)     365일 TTL                           │
  │  trade_aggregations (집계) 365일 TTL                          │
  │  anomaly_alerts (이상탐지) 365일 TTL                          │
  └──────────────────────┬───────────────────────────────────────┘
                         │
                         ▼
  ┌──────────────┐   ┌──────────────────┐   ┌──────────────┐
  │   Grafana    │──▶│  Caddy (Reverse  │──▶│  Cloudflare  │
  │  Dashboard   │   │     Proxy)       │   │   Tunnel     │
  │  12 Panels   │   │  Auto TLS        │   │ 외부 접근    │
  └──────────────┘   └──────────────────┘   └──────────────┘
                                                    │
                                                    ▼
                                          grafana.calmee.store
```

### 리소스 배분 (16GB RAM)

| 컴포넌트 | 메모리 | 비고 |
|----------|--------|------|
| MySQL | 1GB | CDC Source DB |
| Kafka × 3 | 1.5GB | 512MB per broker |
| Zookeeper | 256MB | Kafka coordination |
| Debezium Connect | 512MB | CDC connector |
| Flink (JM + TM) | 1.5GB | Stream processing |
| ClickHouse | 2GB | OLAP storage |
| Grafana | 256MB | Dashboard |
| Producer | 128MB | Upbit WebSocket |
| Caddy | 64MB | Reverse proxy |
| **합계** | **~7.2GB** | **여유: ~8.8GB** |

---

## 🛠️ 기술 스택

| 컴포넌트 | 기술 | 버전 | 역할 |
|----------|------|------|------|
| Source DB | MySQL | 8.0 | CDC 소스 (binlog) |
| CDC | Debezium | 2.5 | 실시간 변경 캡처 |
| Message Queue | Apache Kafka | 3.6 | 이벤트 스트리밍 (3-broker) |
| Stream Processing | Apache Flink | 1.18 | 실시간 집계 + 이상 탐지 |
| OLAP | ClickHouse | 24.1 | 분석 쿼리 + 대시보드 백엔드 |
| Dashboard | Grafana | 10.x | 실시간 시각화 (12 패널) |
| Data Source | Upbit WebSocket | - | 암호화폐 실시간 체결 |
| Reverse Proxy | Caddy | 2.x | HTTPS + 자동 인증서 |
| External Access | Cloudflare Tunnel | - | 외부 접근 (grafana.calmee.store) |
| Language | Java 17 | - | Flink DataStream Job |
| Language | Python 3 | - | Upbit Producer |

---

## 📅 구현 단계

### Phase 1: 인프라 구축 ✅
- [x] Docker Compose 구성 (12개 컨테이너, 메모리 최적화)
- [x] MySQL binlog 설정 (ROW 포맷, server-id, gtid)
- [x] Kafka 3-broker 클러스터 (RF=3, 72시간 보존)
- [x] Zookeeper + 전체 healthcheck 구성

### Phase 2: CDC 파이프라인 ✅
- [x] Debezium MySQL CDC Connector 설정
- [x] INSERT/UPDATE/DELETE 이벤트 캡처 검증
- [x] Connect 내부 토픽 RF 문제 해결 (startup.sh 자동화)
- [x] Kafka 토픽 생성 및 메시지 흐름 확인

### Phase 3: Flink 스트리밍 ✅
- [x] Java DataStream API Job 개발
- [x] 5분 윈도우 집계 (거래량, 체결건수, 매수/매도)
- [x] 이상 탐지 4가지 룰 (LARGE_TRADE, PRICE_SPIKE, VOLUME_SURGE, RAPID_TRADES)
- [x] ClickHouse JDBC Sink (3개 테이블)
- [x] NullSafeStringSchema (Debezium tombstone 방어)
- [x] Restart 전략 (fixedDelay 3회/10초)

### Phase 4: ClickHouse + Grafana ✅
- [x] ClickHouse 테이블 설계 (MergeTree, 365일 TTL)
- [x] Grafana 프로비저닝 (datasource + dashboard JSON)
- [x] 12개 패널 대시보드 구성
- [x] Caddy 리버스 프록시 + Cloudflare Tunnel 외부 접근

### Phase 5: 암호화폐 실시간 수집 ✅
- [x] Upbit WebSocket Producer (Python, 5개 마켓)
- [x] MySQL 스키마 전환 (주식 → 암호화폐)
- [x] Flink Job 수정 (파싱, 집계, 이상탐지 전환)
- [x] 데이터 라이프사이클 관리 (MySQL 7일, Kafka 72시간, ClickHouse 365일)

### Phase 6: 이상 탐지 고도화 + 장애 복구 ✅
- [x] 업비트 이상거래 감시정책 기반 임계값 재설계
- [x] 학술 논문 근거 반영 (EWMA 동적 임계값)
- [x] MySQL cleanup → Flink crash 장애 복구 (tombstone NPE)
- [x] Grafana annotation 자동 동기화 (cron 매분)
- [x] 숫자 포맷 통일 (₩ 단위, 콤마 구분)

---

## 🔧 운영 이슈 & 트러블슈팅

### 이슈 1: MySQL Cleanup DELETE 폭주 → Flink 장애

| 항목 | 내용 |
|------|------|
| **현상** | 2일간 ClickHouse 데이터 적재 중단 |
| **원인** | 일 1회 50K건 DELETE → Debezium tombstone 메시지 → Flink NPE → Job FAILED |
| **근본 원인** | `SimpleStringSchema`가 null 바이트 처리 불가 + CdcEventParser DELETE 미처리 |
| **해결** | NullSafeStringSchema 구현, DELETE 스킵, 매시간 25K건 분산 삭제로 전환 |
| **교훈** | CDC 파이프라인에서 대량 DML은 반드시 시간 분산 처리 |

### 이슈 2: Flink Checkpoint Offset 복원 문제

| 항목 | 내용 |
|------|------|
| **현상** | Kafka offset 리셋해도 Flink가 과거 offset으로 회귀 |
| **원인** | Flink checkpoint가 Kafka consumer group보다 우선 |
| **해결** | Checkpoint 삭제 + `OffsetsInitializer.latest()` 변경 |
| **교훈** | Flink offset 관리는 checkpoint 우선, consumer group 리셋만으로 불충분 |

### 이슈 3: 이상 탐지 과다 알림

| 항목 | 내용 |
|------|------|
| **현상** | 시간당 651건 알림 (DOGE 1원 변동이 매번 발동) |
| **원인** | 고정 임계값이 암호화폐 변동성 특성 미반영 |
| **해결** | 업비트 정책 매핑 + 학술 근거 + 실측 데이터 기반 재조정 → 시간당 72건 (89% 감소) |
| **교훈** | 도메인 특성 반영한 임계값 설정, 실시간 데이터로 검증 필수 |

### 이슈 4: Kafka Cluster ID 불일치

| 항목 | 내용 |
|------|------|
| **현상** | Broker 재시작 시 ClusterIdMismatch로 기동 실패 |
| **원인** | Docker volume 재생성 시 기존 meta.properties와 충돌 |
| **해결** | startup.sh에서 Connect 내부 토픽 자동 재생성 로직 추가 |

---

## 📊 성능 지표

| 지표 | 목표 | 실측 |
|------|------|------|
| E2E CDC Latency | < 10ms | **평균 3.3ms** ✅ |
| Throughput | > 100 TPS | **초당 ~8건 (Upbit 제공량)** ✅ |
| 데이터 정합성 | 100% | **MySQL ↔ ClickHouse 일치** ✅ |
| 장애 복구 시간 | < 5분 | **Flink restart 30초 이내** ✅ |
| 메모리 사용 | < 14GB | **~7.2GB (여유 8.8GB)** ✅ |
| 24시간 운영 | ✅ | **10일+ 연속 가동** ✅ |
| 외부 접근 | ✅ | **grafana.calmee.store** ✅ |
| 총 적재 | - | **667만건+ (10일)** |
| 이상 탐지 | 의미 있는 알림 | **~72건/시간** ✅ |

---

## 📁 프로젝트 구조

```
cdc-realtime-pipeline/
├── README.md
├── docker-compose.yml
├── .env / .env.example
│
├── producer/                    # Upbit WebSocket Producer
│   ├── producer.py              # 실시간 체결 데이터 수집
│   ├── Dockerfile
│   └── requirements.txt
│
├── mysql/
│   ├── init.sql                 # crypto_trades 스키마
│   └── my.cnf                   # binlog + event_scheduler 설정
│
├── debezium/
│   └── connector-config.json    # MySQL CDC Connector 설정
│
├── kafka/
│   └── config/                  # Broker 설정
│
├── flink/
│   ├── pom.xml
│   ├── Dockerfile               # 멀티스테이지 빌드
│   └── src/main/java/com/cdc/pipeline/
│       ├── CdcPipelineJob.java          # 메인 Job (Source → Sink)
│       ├── model/
│       │   └── CryptoTradeEvent.java    # 체결 이벤트 POJO
│       ├── function/
│       │   ├── CdcEventParser.java      # Debezium JSON 파싱 (null-safe)
│       │   ├── AnomalyDetector.java     # 이상 탐지 4가지 룰
│       │   ├── TradeAggregator.java     # 5분 윈도우 집계
│       │   └── NullSafeStringSchema.java # Tombstone 방어 Deserializer
│       └── sink/
│           ├── ClickHouseRawSink.java   # 원본 체결 Sink
│           ├── ClickHouseAggSink.java   # 집계 Sink
│           └── ClickHouseAlertSink.java # 이상탐지 Sink
│
├── clickhouse/
│   └── init.sql                 # 3개 테이블 (trades, aggregations, alerts)
│
├── grafana/
│   └── provisioning/
│       ├── datasources/         # ClickHouse datasource
│       └── dashboards/
│           └── json/cdc-pipeline.json  # 12개 패널 대시보드
│
├── scripts/
│   ├── startup.sh               # 전체 파이프라인 기동
│   ├── build-flink-job.sh       # Flink Job 빌드 + 배포
│   ├── check-health.sh          # 헬스체크
│   └── sync-annotations.sh     # Grafana annotation 자동 동기화
│
└── docs/
    ├── 02-infrastructure.md     # Phase 1-2 인프라 + CDC
    ├── 03-cdc-pipeline.md       # Debezium 설정 상세
    ├── 04-flink-streaming.md    # Flink Job 설계
    ├── 05-clickhouse-grafana.md # ClickHouse + Grafana
    └── 06-phase6-record.md      # 이상탐지 고도화 + 장애복구
```

---

## 🎤 면접 예상 질문

### Q1. 왜 On-Premise를 선택했나요?
> "토스 JD에 'On-Premise 클러스터 구축'이 명시되어 있어서, 클라우드 관리형 서비스가 아닌 물리 서버에서 직접 구축하고 24시간 운영하며 실제 장애 대응까지 경험했습니다."

### Q2. 16GB 메모리에서 어떻게 최적화했나요?
> "12개 컨테이너를 7.2GB 내에서 운영합니다. Kafka broker당 512MB, Flink TaskManager 1GB로 제한하고, MySQL은 7일 보존 + 매시간 분산 삭제, ClickHouse는 365일 TTL로 디스크 관리를 자동화했습니다."

### Q3. CDC 파이프라인에서 가장 어려웠던 장애는?
> "MySQL의 정기 cleanup DELETE가 Debezium tombstone 메시지를 대량 생성하여 Flink Job이 죽은 사례입니다. NullSafeStringSchema 구현, DELETE 이벤트 스킵, cleanup 시간 분산으로 해결했고, 이를 통해 CDC 파이프라인에서 대량 DML의 위험성을 체감했습니다."

### Q4. 이상 탐지 임계값은 어떻게 설정했나요?
> "업비트 이상거래 감시정책(가상자산이용자보호법 7가지 유형)을 분석하고, 'Detecting Crypto Pump-and-Dump Schemes' 논문의 EWMA 기반 접근을 적용했습니다. 초기 651건/시간에서 실측 데이터 기반 조정으로 72건/시간(89% 감소)까지 최적화했습니다."

### Q5. 왜 Debezium CDC를 선택했나요?
> "직접 binlog 파싱 대비, Debezium이 스키마 변경 추적, exactly-once 전달, Kafka Connect 통합을 제공합니다. 다만 tombstone 메시지 처리는 별도 방어 코드가 필요하다는 점도 경험했습니다."

### Q6. Kafka를 3-broker로 구성한 이유는?
> "Replication Factor 3으로 1대 장애 시에도 데이터 유실 없이 자동 failover됩니다. 실제로 broker 1대 다운 시뮬레이션에서 파이프라인 중단 없이 정상 동작을 확인했습니다."

---

## 🔗 관련 프로젝트

- [FDS Pipeline Lab](https://github.com/Aguantar/fds-pipeline-lab) — 이상거래 탐지 파이프라인 (Redis+Consumer로 TPS 70→17,500, 250배 최적화)

---

## 🖥️ 서버 환경

| 항목 | 스펙 |
|------|------|
| 하드웨어 | Mini PC (On-Premise) |
| CPU | Intel N100 (4코어) |
| RAM | 16GB |
| Disk | 500GB SSD |
| OS | Ubuntu 24.04 |
| 운영 | 24시간 상시 (10일+ 가동 중) |
