# 🚀 On-Premise Real-time CDC Pipeline

> **물리 서버 기반 암호화폐 실시간 변경 데이터 캡처 및 이상 탐지 플랫폼**
>
> 🔗 **Live 운영**: 24시간 상시 가동 (Grafana·Airflow는 인증 뒤 운영 — 요청 시 통제된 라이브 데모 제공)

## 💡 이 프로젝트가 증명하는 것

| 질문 | 답 |
|------|-----|
| 왜 On-Premise인가? | 클라우드가 아닌 물리 서버에서 직접 구축/운영 경험 |
| 왜 CDC인가? | Debezium + Kafka Connect 기반 실시간 입수 파이프라인 구축 능력 |
| 왜 24시간 운영인가? | 실제 프로덕션 환경의 장기 운영 이슈(장애 복구, 용량 관리) 해결 경험 |
| 왜 실시간인가? | 배치가 아닌 Flink 스트리밍 처리 + 이상 탐지 능력 |

**결국 이 프로젝트는:**
- ✅ On-Premise 클러스터 **구축/운영** 경험
- ✅ 제한된 리소스(16GB)에서 **30개 컨테이너 최적화** 경험
- ✅ 실시간 CDC 파이프라인 **설계/구현** 능력 + 호가 직접 발행 경로(2026-09)
- ✅ **지연·유실·중복을 직접 찾아 고친 기록** — 중복 감사(docs/07), 적재 지연 사고(docs/08), 체크포인트 비대(docs/10), 결정 근거 전부 docs/worklog.md
- ✅ Airflow 기반 **배치 오케스트레이션** (Custom Operator, Dynamic Task Mapping, XCom)
- ✅ dbt 기반 **데이터 변환 계층** (staging → intermediate → marts 3계층)
- ✅ 장애 대응 및 **다중 모니터링** 체계 (Grafana 2개 대시보드 + Slack + Gmail)
- ✅ 도메인 기반 **이상 탐지** 룰 엔진 설계

을 보여주기 위한 프로젝트입니다.

---

## 📊 실시간 모니터링 대시보드

> **🔗 실시간 대시보드**: Grafana 12패널 (아래 캡처 · 라이브 데모는 요청 시 제공)

<img width="2527" height="1235" alt="image" src="https://github.com/user-attachments/assets/edca902b-962a-4738-a538-f9ab973200a2" />


### 대시보드 레이아웃

```
┌─────────────────┬─────────────────┬─────────────────┬─────────────────┬─────────────────┐
│  Active Alerts  │  Total Trades   │ Avg CDC Latency │  Total Volume   │ Markets Tracked │
│   (이상 탐지)    │  (총 체결 건수)  │ (평균 지연시간)  │(최근1시간 거래액)│ (모니터링 마켓)  │
│      ~13/hr     │   91,061,136    │   p50 3ms       │    ₩41.1B       │       5         │
└─────────────────┴─────────────────┴─────────────────┴─────────────────┴─────────────────┘
┌──────────────────────────────────────┬──────────────────────────────────────┐
│  BTC Price (실시간 BTC 가격)           │  Bid vs Ask (매수/매도 비율)           │
│  🔴 빨간 점선 = 이상 탐지              │  ██ BTC  ██ XRP  ██ ETH  █ SOL █DOG │
│  price(녹) / low(노) / high(파)       │  마켓별 매수/매도 건수 막대 차트        │
└──────────────────────────────────────┴──────────────────────────────────────┘
┌──────────────────────────────────────┬─────────────────────────────┬───────┐
│  Trade Volume (5분 총 거래금액)        │  CDC Latency (CDC 지연시간)  │🟢LIVE │
│  5개 코인 합산 라인 차트 (₩ 단위)       │  avg(녹색) / max(주황) 추이  │       │
└──────────────────────────────────────┴─────────────────────────────┴───────┘
┌─────────────────────────────────────────────────────────────────────────────┐
│  Anomaly Alerts (이상 탐지 내역)                                             │
│  시간 | alert_type | market | message (콤마 포맷) | value | threshold       │
│  최근 50건, value/threshold 숫자 콤마 포맷 적용                               │
└─────────────────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────────────┐
│  Recent Trades (최근 체결 내역)                                               │
│  시간 | market | ask_bid | trade_price | volume | amount | cdc_latency_ms  │
│  최근 5분 이내 20건 표시                                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 패널 상세 (12개)

**상단 KPI (5개)** — 파이프라인 핵심 지표 한눈에

| 패널 | 데이터 범위 | 설명 |
|------|-----------|------|
| **⚠ Active Alerts (이상 탐지)** | 최근 1시간 | 이상 탐지 룰에 걸린 알림 건수 (빨간 배경 강조) |
| **Total Trades (총 체결 건수)** | 전체 누적 | 파이프라인 가동 이후 총 체결 건수 (현재 9,100만+) |
| **Avg CDC Latency (평균 지연시간)** | 최근 1시간 | MySQL → ClickHouse 평균 CDC 지연 (목표: <10ms, 실측: ~3ms) |
| **Total Volume (최근 1시간 거래금액)** | 최근 1시간 | 5개 마켓 합산 체결 금액 (₩ 자동 포맷: K, M, B) |
| **Markets Tracked (모니터링 마켓)** | 고정값 | BTC, ETH, XRP, SOL, DOGE (5개) |

**중단 차트 (4개) + Pipeline Status** — 시장 흐름 + 이상 탐지 시각화

| 패널 | 위치 | 데이터 소스 | 설명 |
|------|------|-----------|------|
| **BTC Price (실시간 BTC 가격)** | 좌상 | `crypto_trades` | 분 단위 평균/최저/최고 + 🔴 **이상 탐지 빨간 점선** (Grafana Annotation) |
| **Bid vs Ask (매수/매도 비율)** | 우상 | `trade_aggregations` | 마켓별 매수/매도 건수 막대 차트 (1시간 집계, 자동 갱신) |
| **Trade Volume (5분 총 거래금액)** | 좌하 | `trade_aggregations` | 5개 코인 합산 거래금액 라인 차트 (₩ 단위, Flink 5분 윈도우 집계) |
| **CDC Latency (CDC 지연시간)** | 우하 | `crypto_trades` | 평균(녹색)/최대(주황) 레이턴시 추이 (ms 단위) |
| **Pipeline Status (파이프라인 상태)** | 우하 끝 | `crypto_trades` | 5분 내 데이터 유입 여부 (🟢 LIVE / 🔴 STALE, 글씨색 표시) |

**하단 테이블 (2개)** — 상세 데이터 조회

| 패널 | 표시 건수 | 특징 |
|------|----------|------|
| **Anomaly Alerts (이상 탐지 내역)** | 최근 50건 | alert_type, market, message, value/threshold **콤마 포맷** 적용 |
| **Recent Trades (최근 체결 내역)** | 최근 5분 내 20건 | trade_price (₩ 포맷), trade_amount (₩ 포맷), cdc_latency_ms |
---

## 🔍 FDS 이상 탐지 규칙

### 설계 배경

업비트는 **가상자산이용자보호법**에 따라 7가지 불공정거래 유형을 모니터링합니다. 우리 파이프라인은 체결(trade) 데이터만 수신하므로, 7가지 중 **체결 기반 3가지**를 구현하고, 1가지는 데이터 분석 결과 비활성화했습니다.

| # | 업비트 감시 유형 | 설명 | 파이프라인 매핑 | 구현 여부 |
|---|-----------------|------|---------------|----------|
| 1 | 가장·통정성 매매 | 자전거래, 권리이전 없는 가장매매 | - | ❌ 계좌 정보 없음 |
| 2 | 허수성 매매 | 체결 불가능한 대량 호가 제출 | - | 🔜 2026-09 호가(L2 스냅샷) 수집 시작 — "체결 없이 사라진 잔량" 추론으로 확장 예정 |
| 3 | 취소·정정 과다 | 체결률 극히 낮은 반복 주문 | - | 🔜 호가 잔량 감소 × 체결 대조로 부분 추론 예정 (주문 단위 데이터는 공개 API에 없음) |
| 4 | **특정종목 매매집중** | 과도한 매매로 시세 영향 | → ~~RAPID_TRADES~~ | ⚠️ 비활성화 (아래 참고) |
| 5 | **체결관여 과다** | 전체 체결 대비 과도 집중 | → **VOLUME_SURGE**, **LARGE_TRADE** | ✅ |
| 6 | 주문관여 과다 | 전체 주문 대비 과도 제출 | - | ❌ 주문 데이터 없음 |
| 7 | **시세관여 과다** | 시세 변동에 과도 관여 | → **PRICE_SPIKE** | ✅ |

> 참고: 업비트는 AI/ML 기반 패턴 분석으로 구체적 수치를 비공개합니다. ([감시정책 문서](https://static.upbit.com/guide/market_surveillance_policy.pdf))

### 임계값 설정 근거

**학술 논문**: *"Detecting Crypto Pump-and-Dump Schemes"* (2025, arXiv:2503.08692)
- 고정 임계값이 아닌 **EWMA(지수가중이동평균) + 변동성 기반 동적 임계값** 접근
- 코인별 과거 패턴 대비 이상치를 탐지 → 우리 VOLUME_SURGE에 EMA 기반 동적 임계값 적용

### 탐지 규칙 상세 (3가지 활성 + 1가지 비활성화)

#### 1. LARGE_TRADE — 대량 체결 탐지

| 마켓 | 임계값 | 설정 근거 |
|------|--------|----------|
| KRW-BTC | ₩5억 | 업비트 BTC 일 거래대금 ~1조원, 단일 체결 5억은 상위 0.01% 수준 |
| KRW-ETH | ₩3억 | BTC 대비 거래대금 60% 수준 반영 |
| 기타 (XRP, SOL, DOGE) | ₩1억 | 알트코인 일 거래대금 대비 유의미한 대량 거래 기준 |

> 업비트 대응 유형: **체결관여 과다** — 전체 체결 대비 과도하게 집중된 거래

#### 2. PRICE_SPIKE — 급격한 가격 변동

| 마켓 | 임계값 | 설정 근거 |
|------|--------|----------|
| KRW-BTC | 2% | BTC 9,700만원 기준 2% = 194만원 변동, 정상 변동폭(0.1~0.5%) 대비 유의미 |
| 기타 | 3% | DOGE 136→137원(0.73%)이 매번 발동하는 문제 해결, 호가 단위 고려 |

> 업비트 대응 유형: **시세관여 과다** — 시세 변동에 과도하게 관여하는 거래

> **v1 → v2 조정 이유**: 초기 0.5% 고정 임계값에서 DOGE 1원 변동(0.73%)이 매번 PRICE_SPIKE로 탐지됨. 코인별 호가 단위와 변동성 특성을 반영하여 마켓별 동적 임계값 적용

#### 3. VOLUME_SURGE — 거래량 급증

| 파라미터 | 값 | 설정 근거 |
|---------|-----|----------|
| 기준 | EMA × 150배 | 24시간 실측 데이터 분석: p90=3.5x, p95=5.4x → 상위 10% 이상만 탐지 |
| EMA alpha | 0.05 | 최근 20건 가중 평균, 급격한 변화에 민감하되 노이즈 필터링 |
| 최소 학습 | 50건 | 충분한 데이터 없이 오탐 방지 (파이프라인 시작 직후 알림 폭주 방지) |

> 업비트 대응 유형: **체결관여 과다** — 전체 체결 대비 과도하게 집중된 거래량

> **v1 → v2 → v3 조정 과정**:
> - v1 (EMA×10): 시간당 512건(전체 알림의 74%) → 암호화폐 변동성 과소 반영
> - v2 (EMA×50): 시간당 60건으로 감소했으나, 실측 분석 결과 중앙값이 임계값의 1.4배에 불과 (간신히 초과하는 노이즈)
> - v3 (EMA×150): 24시간 알림 분포 분석(p90=3.5x) 기반, **진짜 급증만 탐지** → 시간당 ~12건

#### 4. ~~RAPID_TRADES~~ — 단기간 다수 체결 (비활성화)

| 파라미터 | v2 값 | 비활성화 사유 |
|---------|-------|-------------|
| 기준 | 10초 내 100건 | Upbit WebSocket API 전송 한계가 ~100건/10초 |

> 업비트 대응 유형: **특정종목 매매집중** — 과도한 매매로 시세에 영향을 미치는 행위

> **비활성화 근거 (데이터 분석 결과)**:
> - 24시간 동안 73건 탐지, **전부 정확히 100건** (101건 이상 단 한 건도 없음)
> - ClickHouse에서 10초 윈도우 분석 결과, BTC 최대 체결수도 100건으로 동일
> - 이는 **Upbit WebSocket API의 전송 한계**이지 실제 이상거래가 아님
> - 실제 거래소 내부 데이터(raw order book)라면 유효한 규칙이나, 외부 API 기반에서는 무의미
> - `Integer.MAX_VALUE`로 설정하여 비활성화, 코드 구조는 유지 (추후 데이터소스 변경 시 재활성화 가능)

### 임계값 최적화 결과

| 지표 | v1 (초기) | v2 | v3 (현재) | 총 개선율 |
|------|----------|-----|----------|----------|
| 시간당 총 알림 | 651건 | 72건 | **~13건** | **98% 감소** |
| VOLUME_SURGE | 512건 | ~60건 | **~12건** | EMA×10 → ×50 → ×150 |
| PRICE_SPIKE | 131건 | ~0건 | **~0건** | DOGE 오탐 제거 (유지) |
| RAPID_TRADES | 28건 | ~10건 | **0건** | 비활성화 (API 한계) |
| LARGE_TRADE | 20건 | ~2건 | **~12건** | 유지 (적정) |

> **v3 조정 방법론**: ClickHouse에 적재된 24시간 알림 데이터의 `value/threshold` 분포를 분석하여, p90(상위 10%) 기준으로 "간신히 초과하는 노이즈"와 "진짜 이상치"를 분리

---

## 🔔 n8n 자동 알림 시스템

![n8n Workflow](docs/images/n8n-workflow.png)

### 아키텍처

```
┌─────────────────┐
│ Schedule Trigger │ (매 1분)
└────────┬────────┘
         ▼
┌─────────────────┐
│   ClickHouse    │  이상거래 건수 + 파이프라인 상태 + 알림 상세
│   HTTP 쿼리     │  (Docker 네트워크로 직접 접근)
└────────┬────────┘
         ▼
┌─────────────────┐
│  Parse & Combine │  숫자 포맷 (콤마 구분) + 알림 분류
└────────┬────────┘
         │
    ┌────┴────────┐
    ▼             ▼
┌────────┐   ┌────────┐
│  IF    │   │  IF    │
│이상거래│   │파이프  │
│ >0건?  │   │라인    │
│        │   │ 장애?  │
└───┬────┘   └───┬────┘
    │             │
    ▼             ▼
 [Slack]       [Slack]
 [Gmail]       [Gmail]
 FDS 알림     CDC 장애
```

### 알림 종류

| 알림 | 조건 | 채널 | 의미 |
|------|------|------|------|
| **🚨 FDS 이상거래 탐지** | anomaly_count > 0 (최근 1분) | Slack + Gmail | 이상 탐지 룰 발동, 상세 내역 포함 |
| **🔴 CDC 파이프라인 장애** | 최근 5분간 데이터 0건 | Slack + Gmail | 파이프라인 중단, 복구 가이드 포함 |

### 알림 메시지 예시

**Slack 알림**

![Slack Alert](docs/images/slack-alert.png)

**Gmail 알림**

![Gmail Alert](docs/images/gmail-alert.png)

**FDS 이상거래 탐지 (Slack)**

```
🚨 FDS 이상거래 탐지!

최근 1분간: 3건
최근 5분 거래: 2,476건
총 적재 건수: 6,764,002건
시간: 2026-02-23 14:42:19

상세 내역:
• VOLUME_SURGE | KRW-BTC: 거래량 EMA 대비 52.3배 급증
• VOLUME_SURGE | KRW-ETH: 거래량 EMA 대비 61.7배 급증

📊 Grafana 대시보드 바로가기
```

**CDC 파이프라인 장애 (Gmail)**

```
🔴 CDC 파이프라인 장애 알림

상태: 데이터 유입 중단
최근 5분 거래: 0건
총 적재 건수: 6,764,002건

확인 사항:
• Flink Job 상태 확인
• Kafka LAG 확인
• MySQL 접속 확인

📊 Grafana 대시보드 바로가기
```

---

## 📋 프로젝트 개요

### 데이터 소스
- **체결(trade)**: Upbit WebSocket, KRW 전 마켓 **287개** (2026-09-09 확장. 그 전 7개월은 BTC/ETH/XRP/SOL/DOGE 5개)
  - 287마켓 실측: 평균 25~30 rows/s, 오전 피크 53.6 msg/s, 일 약 400만 건 (5개 마켓 시절: 4.17 rows/s, 일 36만 건)
- **호가(orderbook)**: Upbit WebSocket `orderbook.15`, KRW 287마켓 전체 스냅샷 (2026-09-09 신설)
  - 실측: 154~262 msg/s(시간대별), payload 158~273 KB/s, 일 약 2,200만 건

### 파이프라인 흐름
```
[실시간 스트리밍 — 체결: CDC 경로]
Upbit WebSocket → MySQL → Debezium CDC → Kafka (3-broker) → Flink → ClickHouse → Grafana
                                                                          │
[실시간 스트리밍 — 호가: 직접 발행 경로 (2026-09)]                            │
Upbit WebSocket → orderbook-collector → Kafka upbit.orderbook.v1 → Flink → ClickHouse (raw 7일 / 1분 파생 365일)
                                                                          │
[배치 오케스트레이션]                                                       │
Airflow (Scheduler) → dbt (staging → intermediate → marts) ────────────────┘
    │                                                                      │
    └─ health_check (10분) ─→ 이상 시 Slack 알림                            │
    └─ daily_pipeline (01:00 KST) ─→ 품질검증 + 일일 리포트 → Slack         │
                                                                           │
[실시간 알림]                                                               │
n8n (매분) → ClickHouse 조회 → FDS 이상거래 / CDC 장애 → Slack + Gmail
```

### 차별화 포인트

| 일반 프로젝트 | 이 프로젝트 |
|--------------|-------------|
| AWS/GCP 관리형 서비스 | **On-Premise 물리 서버 직접 구축** |
| 로컬에서 잠깐 테스트 | **24시간 상시 운영 (2026-02부터 200일+, 체결 9,200만건 적재)** |
| 시연할 때만 실행 | **24시간 실제 운영** (요청 시 통제된 라이브 데모) |
| 무제한 리소스 | **16GB 메모리에서 30개 컨테이너 공존 (메모리 제한·실측 기반 배분)** |
| 시뮬레이션 데이터 | **Upbit 실시간 체결(287마켓) + 호가(287마켓) 실데이터** |
| 감으로 튜닝 | **사고 분석과 실측으로 결정** — 37시간 적재 지연 사고 분석(docs/08), 체크포인트 625MB→18KB(docs/10), 호가 압축률 14배 실측(docs/11), 모든 결정 근거는 docs/worklog.md |
| 고정 임계값 이상 탐지 | **업비트 정책 + 학술 논문 + 실측 분포 분석 기반 동적 임계값** |
| cron으로 dbt 실행 | **Airflow 오케스트레이션 (Custom Operator + Dynamic Task Mapping + Slack 리포트)** |
| 탐지만 하고 끝 | **다중 알림 (n8n 실시간 + Airflow 일일 리포트)** |

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
  │  + dbt marts (daily_summary, volume_spike, alert_rate)       │
  └──────┬──────────────────────┬───────────────┬───────────────┘
         │                      │               │
         ▼                      ▼               ▼
  ┌──────────────┐    ┌──────────────┐   ┌──────────────┐
  │   Grafana    │    │   Airflow    │   │     n8n      │  매분 폴링
  │  2 Dashboard │    │ Orchestrator │   │  Monitoring  │
  │  (CDC +      │    │              │   └──────┬───────┘
  │   Airflow)   │    │ ┌──────────┐ │          │
  └──────┬───────┘    │ │health_   │ │   ┌──────┼──────────┐
         │            │ │check     │ │   ▼      ▼          ▼
         │            │ │(*/10min) │ │ [Slack] [Slack]  [Gmail]
         │            │ └──────────┘ │  FDS     CDC     FDS+CDC
         │            │ ┌──────────┐ │
         │            │ │daily_    │ │
         │            │ │pipeline  │──→ dbt run/test
         │            │ │(01:00KST)│      → Dynamic 코인별 품질검증
         │            │ └──────────┘      → 일일 리포트 → Slack
         │            └──────────────┘
         ▼                    │
  ┌──────────────┐    StatsD → Prometheus
  │    Caddy     │
  │ (Reverse     │
  │   Proxy)     │
  └──────┬───────┘
         │
         ▼
  Grafana (대시보드)
  Airflow (오케스트레이션)
```

### 2026-09 확장: 호가 경로 + Flink 재구성

위 다이어그램은 체결(CDC) 경로다. 2026-09-09에 아래가 추가·변경됐다(상세 `docs/10`, `docs/11`).

```
  Upbit WS orderbook.15 (287마켓, 단일 커넥션)
        │  154~262 msg/s
        ▼
  orderbook-collector (Python, confluent-kafka)   key=market, zstd, idempotent producer
        │
        ▼
  Kafka upbit.orderbook.v1   6 파티션 · RF2 · 24h / 6GB·파티션   (zstd 후 415 B/msg → RF2 약 18.5GB/일)
        │
        ▼
  Flink OrderbookJob (슬롯 1, 이벤트타임 1분 윈도우)   ─┐  같은 TaskManager (2g, 슬롯 4, state.backend=hashmap)
        │                                              │  잡 3개: CDC(2슬롯) + Orderbook(1) + Circuit Connect(1)
        ├─▶ orderbook_raw  (15단 Array(Float64)×4, TTL 7일, 39.7 B/행 on-disk = JSON 대비 26배 압축)
        └─▶ orderbook_1m   (mid·spread·depth imbalance 1/5/15단·recv lag, TTL 365일)
```

| 변경 | 전 | 후 | 근거 |
|---|---|---|---|
| Flink 상태 백엔드 | RocksDB, 체크포인트 625MB(MANIFEST 비대) | hashmap, **17.8KB**, e2e 1.4s → 51ms | 키드 상태가 수십 KB뿐임을 로컬 db 디렉터리로 실측 |
| Flink TaskManager | 1g, task heap 25.6MiB, Metaspace 90% | 2g, task heap 692MiB, 슬롯 4 | Flink 메모리 모델 계산 |
| producer flush | 2초당 1배치(20행) = 최대 10 rows/s | 버퍼 소진까지 반복, 상한 2,500 rows/s, `buffer=` 지표 | 8월 37시간 적재 지연 사고 원인 |
| Kafka 시작 오프셋 | `latest()` (재시작 시 유실) | `committedOffsets(LATEST)` | 재시작 유실 방지 |
| 체결 이벤트 | 6필드 | + best bid/ask 4필드 (Upbit 신규 필드) | 호가 없이도 스프레드 확보 |
| MySQL 정리 | 매시 25K DELETE | 10분 40K DELETE, Debezium tombstone 비활성 | 전 코인 유입 400만/일 대응. 관찰 뒤 DROP PARTITION 전환 예정 |

### 리소스 배분 (16GB RAM, 30개 컨테이너)

| 컴포넌트 | 메모리 Limit | 비고 |
|----------|-------------|------|
| MySQL | 1GB | CDC Source DB |
| Kafka × 3 | 3.75GB | 1.25GB per broker (단일 호스트라 HA 아님 — 복제 의미론·장애 실험용, 실험 뒤 1브로커 축소 예정) |
| Zookeeper | 384MB | Kafka coordination (실험 뒤 KRaft 전환 예정) |
| Debezium Connect | 1.25GB | CDC connector |
| Flink (JM 896M + TM 2304M) | 3.2GB | 잡 3개, 슬롯 4, hashmap |
| ClickHouse | 1.75GB | OLAP storage (호가 인서트 추가 후 1.25→1.75GB) |
| Grafana | 256MB | 2개 대시보드 (CDC + Airflow) |
| Airflow (Webserver + Scheduler) | 1.28GB | 640MB each |
| Airflow PostgreSQL | 256MB | Airflow 메타 DB |
| StatsD Exporter | 128MB | Airflow 메트릭 변환 |
| Prometheus | 256MB | 메트릭 수집/저장 |
| Producer | 256MB | Upbit WebSocket 체결 (287마켓) |
| Orderbook Collector | 256MB | Upbit WebSocket 호가 (287마켓) |
| Kafka UI | 384MB | 클러스터 모니터링 |
| **제한 합계** | **~14.3GB** | **실사용 합 약 7.5GB (2026-09-09 실측), 비CDC 컨테이너 11개는 별도 mem_limit** |

---

## 🛠️ 기술 스택

| 컴포넌트 | 기술 | 버전 | 역할 |
|----------|------|------|------|
| Source DB | MySQL | 8.0 | CDC 소스 (binlog) |
| CDC | Debezium | 2.5 | 실시간 변경 캡처 |
| Message Queue | Apache Kafka | 3.6 | 이벤트 스트리밍 (3-broker) |
| Stream Processing | Apache Flink | 1.18 | 실시간 집계 + 이상 탐지 |
| OLAP | ClickHouse | 24.1 | 분석 쿼리 + 대시보드 백엔드 |
| Orchestration | Apache Airflow | 2.8.1 | 배치 오케스트레이션 (2 DAGs, Custom Operator) |
| Data Transform | dbt | 1.7.9 | ClickHouse 데이터 변환 (3계층: staging → intermediate → marts) |
| Dashboard | Grafana | 11.0 | 실시간 시각화 (CDC 12패널 + Airflow 12패널) |
| Metrics | Prometheus | 2.50 | Airflow 메트릭 수집 (StatsD → Prometheus → Grafana) |
| Realtime Alerting | n8n | latest | FDS 이상거래 + CDC 장애 알림 (Slack, Gmail) |
| Daily Report | Airflow + Slack | - | 일일 파이프라인 리포트 (품질검증 + CDC 지연 + 이상탐지 요약) |
| Data Source | Upbit WebSocket | - | 암호화폐 실시간 체결 + 호가(orderbook.15), KRW 287마켓 |
| Orderbook Collector | Python + confluent-kafka | 2.15 | 호가 스냅샷 → Kafka 직접 발행 (zstd, idempotent) |
| Observability | cron + ClickHouse SQL | - | 5분 간격 87개 파이프라인 지표 (`scripts/observe/`), 적재 지연 알림(health_check) |
| Reverse Proxy | Caddy | 2.10 | HTTPS + 자동 인증서 |
| Language | Java 17 | - | Flink DataStream Job |
| Language | Python 3.10 | - | Upbit Producer, Airflow DAGs |

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
- [x] 이상 탐지 4가지 룰 설계 (LARGE_TRADE, PRICE_SPIKE, VOLUME_SURGE, RAPID_TRADES)
- [x] ClickHouse JDBC Sink (3개 테이블)
- [x] NullSafeStringSchema (Debezium tombstone 방어)
- [x] Restart 전략 (fixedDelay 3회/10초)

### Phase 4: ClickHouse + Grafana ✅
- [x] ClickHouse 테이블 설계 (MergeTree, 365일 TTL)
- [x] Grafana 프로비저닝 (datasource + dashboard JSON)
- [x] 12개 패널 대시보드 구성
- [x] Caddy 리버스 프록시 + HTTPS 자동 인증서 외부 접근

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
- [x] v3 임계값: 24시간 분포 분석 기반 VOLUME_SURGE 150x + RAPID_TRADES 비활성화

### Phase 7: n8n 자동 알림 시스템 ✅
- [x] n8n → ClickHouse 네트워크 연결 (Docker 외부 네트워크)
- [x] FDS 이상거래 탐지 알림 (Slack + Gmail)
- [x] CDC 파이프라인 장애 알림 (Slack + Gmail)
- [x] 숫자 포맷 (콤마 구분) + 대시보드 바로가기 링크

### Phase 8: Airflow 오케스트레이션 + dbt ✅
- [x] Airflow 2.8.1 Docker 구축 (LocalExecutor, PostgreSQL 메타 DB)
- [x] Custom Operator 개발 (ClickHouseOperator — HTTP API, FlinkHealthOperator — REST API)
- [x] Custom Hook 개발 (ClickHouseHook — HTTP 인터페이스, 추가 드라이버 불필요)
- [x] DAG 1: `health_check` (10분 간격) — 5개 컴포넌트 병렬 체크 → XCom 수집 → 이상 시 Slack
- [x] DAG 2: `daily_pipeline` (매일 01:00 KST) — dbt run/test → Dynamic Task Mapping 코인별 품질검증 → quality gate → 일일 Slack 리포트
- [x] dbt 3계층 모델 (staging: stg_trades → intermediate: int_ohlcv_1h, int_ohlcv_daily → marts: mart_daily_summary, mart_volume_spike, mart_alert_rate)
- [x] Airflow 메트릭 모니터링 (StatsD → Prometheus → Grafana Airflow Operations 대시보드 12패널)
- [x] Fernet Key 암호화 (Slack Webhook URL 등 시크릿 보호)
- [x] Caddy reverse proxy (HTTPS 자동 인증서)
- [x] 과거 25일치 일일 리포트 Slack 백필
- [x] DAG 테스트 9/9 통과 (pytest)
- [x] 기존 dbt cron 비활성화 → Airflow 완전 이관

### Phase 9: 적재 지연 사고 분석 + Flink 재구성 + 전 코인·호가 확장 (2026-09-09) ✅
- [x] 중복 감사 마감 (`docs/07`): 91.06M 적재 / 89.42M 고유, idempotent producer + 일일 (source_ts, trade_id) 게이트
- [x] 8/29 "건수 반토막" 판별 → 유실 아님, producer 10 rows/s 상한 포화로 **최대 36.9시간 적재 지연** (`docs/08`)
- [x] 호가 확장 사전 검증: Upbit WS 한도(5연결/s, 429), 287마켓 단일 커넥션, count별 크기, 압축, Oracle vs 미니PC 수신 지연 비교 (`~/cdc-orderbook-probe/REPORT.md`, `docs/09`)
- [x] Flink RocksDB → hashmap (체크포인트 625MB → 17.8KB), TM 1g → 2g, savepoint 복원으로 유실 0·중복 0 (`docs/10`)
- [x] producer flush 상한 제거(10 → 2,500 rows/s) + best bid/ask 4필드 ClickHouse까지 통과
- [x] 호가 경로 신설: collector → Kafka → Flink OrderbookJob → orderbook_raw / orderbook_1m (`docs/11`)
- [x] 체결 287마켓 확장 + MySQL 정리 상향 + Kafka retention 상향 + tombstone 비활성, 1시간 dry-run 통과
- [x] health_check: 잡 3개 감시, 적재 지연(source_ts − upbit_timestamp) 알림 추가
- [x] 7일 무변경 관찰 시작 (2026-09-09 06:10 UTC, 태그 `obs-week1-start`, 계획 `docs/12`)
- [ ] 관찰 뒤: 튜닝 → 녹화-재생 증폭 실험(브로커 장애 시나리오) → 브로커 3→1 + KRaft → CDC 유의미화(가상 매매 원장 + 이상탐지 케이스 관리) · MySQL DROP PARTITION 청소 전환 · ReplacingMergeTree

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
| **2026-09 후속** | 체결 토픽 메시지의 84%가 delete+tombstone임을 실측 → `tombstones.on.delete=false`(compact 토픽이 아니라 무용), 전 코인 확장으로 정리 10분×40K. 근본 해결은 관찰 뒤 일 단위 파티션 + DROP PARTITION(행 단위 이벤트가 생기지 않음) |

### 이슈 5: 적재 지연 36.9시간 — 유실로 오인될 뻔한 사고 (2026-08-19 ~ 08-30)

| 항목 | 내용 |
|------|------|
| **현상** | 08-29를 경계로 일별 적재 건수 690K → 364K 급감. 재연결 직후라 "부분 구독 실패 = 유실" 의심 |
| **판별** | 업비트 일봉(외부 기준)과 대조: 적재시각(`source_ts`) 기준 비율은 41~322% 요동, **체결시각(`upbit_timestamp`) 기준은 전 기간 97.5~99.9% 일치 → 유실 아님** |
| **원인** | producer `flush()`가 2초당 1배치(20행)만 INSERT → 최대 10 rows/s. 08-22 시간당 72.75 msg/s 버스트에 큐 108만 행 적체, 8일간 8 rows/s로 배수(포화 서명: 시간당 처리량 CV 0.047) |
| **왜 못 봤나** | health_check·Grafana·Flink 지표가 전부 `source_ts` 이후 구간만 측정. 거래소 체결시각 기준 지연 지표가 없었음 |
| **해결** | flush를 버퍼 소진까지 반복(상한 2,500 rows/s), `buffer=` 지표·경고, health_check에 `source_ts − upbit_timestamp` p50 > 60초 알림 |
| **교훈** | "유실"과 "지연"은 외부 기준(거래소 시각)과 대조해야 구분된다. 지연 지표는 소스 이벤트 시각 기준이어야 한다. 상세 `docs/08-ingest-lag-incident.md` |

### 이슈 6: mem_limit로 n8n 크래시 루프 24분 (2026-09-09)

| 항목 | 내용 |
|------|------|
| **현상** | 비CDC 컨테이너에 mem_limit 적용 후 n8n이 exit 134(`JavaScript heap out of memory`) 23초 간격 57회 재시작, CDC 알림 24분 중단 |
| **원인** | cgroup 제한을 V8가 힙 상한으로 환산(576M → 312MB). 스왑 상태 RSS(270MiB) 기준 1.5배 규칙이 실제 워킹셋을 과소산정 |
| **해결** | n8n 제한 제거(compose 재생성). Node 앱은 `NODE_OPTIONS=--max-old-space-size`와 함께 정해야 함 |
| **교훈** | 스왑이 많은 호스트에서 `docker stats` RSS는 메모리 산정 근거로 부적합 |

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
| **현상** | v1: 시간당 651건 (DOGE 1원 변동 매번 발동), v2: 시간당 72건 (VOLUME_SURGE 간신히 초과하는 노이즈) |
| **원인** | v1: 고정 임계값이 암호화폐 변동성 미반영, v2: EMA×50이 정상 변동의 상단 경계에 위치 |
| **해결** | v3: 24시간 분포 분석(p90=3.5x) 기반 EMA×150 적용 + RAPID_TRADES 비활성화(API 전송한계) → 시간당 ~13건 (31일 실측) |
| **교훈** | 임계값은 도메인 지식 + 실측 데이터 분포 분석(percentile) 기반으로 반복 조정 필수 |

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
| CDC Latency (binlog → Debezium) | < 10ms | **p50: 3ms, p95: 5ms, p99: 7ms** ✅ (단, 이 구간만 재면 producer 앞단 지연을 못 봄 — 이슈 5) |
| 적재 지연 (거래소 체결시각 → MySQL) | p95 < 5s | **287마켓 실측 p50 1.1~1.2s / p95 2.1s / max 2.8s** (2026-09-09 dry-run 1h). 개선 전 최대 36.9시간 |
| 호가 e2e (거래소 → ClickHouse) | p95 < 3s | **p50 849ms / p95 1.9s** (JDBC 배치 2초 창이 대부분) |
| Throughput (체결) | > 100 TPS | **287마켓 25~30 rows/s, 피크 53.6 msg/s (Upbit 제공량이 상한)**; producer 처리 상한 2,500 rows/s |
| Throughput (호가) | - | **154~262 msg/s, 273 KB/s** |
| 데이터 정합성 | 중복 0% | **실측 중복 1.80% — 지배 원인은 46시간 장애 복구 재소비, 상시 유입은 0.0005% (07-dedup-audit.md). (source_ts, trade_id) 일일 감시 게이트 운영. 2026-09-09 재제출 3회 모두 유실 0·중복 0(savepoint)** |
| Flink 체크포인트 | - | **17.8KB / e2e avg 51ms** (RocksDB 시절 625MB / 1,388ms) |
| 장애 복구 시간 | < 5분 | **Flink restart 30초 이내**, 재시작 전략 20회×30초 |
| 메모리 사용 | < 14GB | **used 약 9GB + swap 4GB (30개 컨테이너, 2026-09-09)** |
| 24시간 운영 | ✅ | **2026-02-13 가동 시작, 200일+** ✅ |
| 외부 접근 | ✅ | **인증 뒤 운영 · 요청 시 라이브 데모** ✅ |
| 총 적재 | - | **체결 누적 91.97M행(2026-09-09), 고유 이벤트 기준 89.42M+ (중복 1.80%는 장애 복구 재소비가 지배 원인)** |
| 호가 저장 효율 | - | **on-disk 39.7 B/스냅샷 (압축 전 562B, JSON 1,050B)** → 원본 7일 약 6GB |
| 이상 탐지 | 의미 있는 알림 | **~13건/시간 (v1 대비 98% 감소, 31일 실측)** ✅ |
| 실시간 알림 | 매분 | **n8n → Slack + Gmail** ✅ |
| 일일 리포트 | 매일 01:00 KST | **Airflow → 품질검증 + CDC 지연 + 이상탐지 요약 → Slack** ✅ |
| dbt 품질검증 | 코인별 자동 | **Dynamic Task Mapping 5개 코인 병렬, 100% 통과** ✅ |

---

## 📁 프로젝트 구조

```
cdc-realtime-pipeline/
├── README.md
├── docker-compose.yml
├── .env / .env.example
│
├── producer/                    # Upbit WebSocket Producer (체결 → MySQL, CDC 경로)
│   ├── producer.py              # 287마켓 체결 수집, 버퍼 소진 flush, best bid/ask
│   ├── Dockerfile
│   └── requirements.txt
│
├── orderbook-collector/         # Upbit WebSocket 호가 → Kafka 직접 발행 (2026-09)
│   ├── collector.py             # orderbook.15 287마켓, zstd, idempotent, STATS(lag p50/p95, queue)
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
│       │   ├── AnomalyDetector.java     # FDS 이상 탐지 3가지 룰 (RAPID_TRADES 비활성화)
│       │   ├── TradeAggregator.java     # 5분 윈도우 집계
│       │   └── NullSafeStringSchema.java # Tombstone 방어 Deserializer
│       ├── sink/
│       │   └── ClickHouseSinks.java     # 원본/집계/이상탐지 JDBC Sink (best bid/ask 포함)
│       └── orderbook/                   # 호가 잡 (2026-09, 같은 JAR의 별도 메인)
│           ├── OrderbookJob.java        # Kafka → raw sink + 1분 이벤트타임 윈도우
│           ├── OrderbookParser.java / OrderbookEvent.java
│           ├── OrderbookAggregator.java / OrderbookMinute.java
│           └── OrderbookSinks.java      # Array(Float64) 바인딩
│
├── clickhouse/
│   ├── init.sql                 # 3개 테이블 (trades, aggregations, alerts)
│   ├── orderbook.sql            # orderbook_raw (TTL 7d) / orderbook_1m (TTL 365d)
│   └── config.d/system-logs-ttl.xml  # system.* 로그 14일 TTL
│
├── grafana/
│   └── provisioning/
│       ├── datasources/                # ClickHouse + Prometheus
│       └── dashboards/json/
│           ├── cdc-pipeline.json       # CDC 실시간 대시보드 (12 패널)
│           └── airflow-operations.json # Airflow 운영 대시보드 (12 패널)
│
├── airflow/
│   ├── Dockerfile                      # Custom image (dbt + statsd)
│   ├── webserver_config.py             # 비로그인 Viewer 설정
│   ├── dbt_profiles/profiles.yml       # Docker 네트워크용 dbt 프로필
│   ├── dags/
│   │   ├── health_check.py             # DAG 1: 10분 헬스체크
│   │   └── daily_pipeline.py           # DAG 2: 일일 배치 + Slack 리포트
│   ├── plugins/
│   │   ├── hooks/clickhouse_hook.py    # ClickHouse HTTP API Hook
│   │   ├── operators/
│   │   │   ├── clickhouse_operator.py  # ClickHouse 쿼리 Operator
│   │   │   └── flink_health_operator.py # Flink REST API Operator
│   │   └── callbacks/slack_callbacks.py # Slack 알림 (실패/SLA/일일리포트)
│   └── tests/test_dags.py              # DAG 구조 테스트 (9개)
│
├── dbt_cdc_pipeline/
│   ├── models/
│   │   ├── staging/stg_trades.sql      # VIEW: 원본 정제
│   │   ├── intermediate/
│   │   │   ├── int_ohlcv_1h.sql        # TABLE: 시간별 OHLCV
│   │   │   └── int_ohlcv_daily.sql     # TABLE: 일별 OHLCV
│   │   └── marts/
│   │       ├── mart_daily_summary.sql  # TABLE: 일일 요약 (리포트용)
│   │       ├── mart_volume_spike.sql   # TABLE: 거래량 급등
│   │       └── mart_alert_rate.sql     # TABLE: 이상탐지 비율
│   └── tests/                          # dbt 데이터 테스트
│
├── monitoring/
│   ├── prometheus.yml                  # Prometheus 스크래핑 설정
│   └── statsd_mapping.yml             # Airflow StatsD → Prometheus 매핑
│
├── scripts/
│   ├── startup.sh                      # 전체 파이프라인 기동
│   ├── build-flink-job.sh              # Flink Job 빌드 + 배포
│   ├── sync-annotations.sh            # Grafana annotation 자동 동기화
│   └── observe/collect_metrics.sh      # 7일 관찰용 5분 지표 스냅샷 (87컬럼, crontab)
│
└── docs/
    ├── 02-infrastructure.md
    ├── 03-cdc-pipeline.md
    ├── 04-flink-streaming.md
    ├── 05-clickhouse-grafana.md
    ├── 06-phase6-record.md             # 이상탐지 임계값 + 46시간 장애 복구
    ├── 07-dedup-audit.md               # 중복 적재 감사 (91.06M / 89.42M)
    ├── 08-ingest-lag-incident.md       # 적재 지연 36.9시간 사고 분석 (유실 아님)
    ├── 09-orderbook-phase1-execution.md
    ├── 10-phase2-flink-producer-upgrade.md
    ├── 11-orderbook-launch.md
    ├── 12-observation-plan.md          # 7일 관찰 가설·임계값
    └── worklog.md                      # 결정 표(근거 포함) + 시간순 작업 기록
```

관련 리포: 녹화-재생 증폭 실험 도구와 관찰 데이터 분석은 별도 리포(`pipeline-load-lab`, 준비 중)에 둔다.

---

## 🎤 예상 질문

### Q1. 왜 On-Premise를 선택했나요?
> "AWS같은 클라우드 시스템이 아닌, On-Premise 클러스터 구축을 해보고 싶어서, 클라우드 관리형 서비스가 아닌 물리 서버에서 직접 구축하고 24시간 운영하며 실제 장애 대응까지 경험했습니다."

### Q2. 16GB 메모리에서 어떻게 최적화했나요?
> "30개 컨테이너에 전부 메모리 제한을 걸고 실측으로 배분합니다. 2026-09 기준 Kafka broker당 1.25GB, Flink TaskManager 2GB(메모리 모델로 task heap 692MiB 확보), ClickHouse 1.75GB, 실사용 합은 약 7.5GB입니다. MySQL은 7일 보존 + 10분 단위 분산 삭제, ClickHouse는 체결 365일·호가 원본 7일 TTL, system 로그 14일 TTL로 디스크를 자동 관리합니다. 한 번은 스왑 상태의 RSS 기준으로 제한을 잡았다가 n8n이 24분간 크래시 루프에 빠진 적이 있어, 지금은 재기동 후 실사용을 다시 재서 정합니다."

### Q3. CDC 파이프라인에서 가장 어려웠던 장애는?
> "두 가지입니다. 하나는 MySQL cleanup DELETE 5만 건이 Debezium delete+tombstone 10만 메시지를 만들고 파서의 NPE로 Flink가 46시간 멈춘 사고입니다. NullSafeStringSchema, DELETE 스킵, 삭제 분산으로 해결했습니다. 다른 하나는 더 교묘했는데, 8월에 일별 적재 건수가 반토막 나서 유실을 의심했지만 업비트 일봉과 체결시각 기준으로 대조하니 유실은 0이었고, producer가 2초당 20행만 쓰는 구조라 최대 36.9시간 적재 지연이 났던 겁니다. 기존 지표가 전부 적재 이후 구간만 재고 있어서 못 본 거였고, 거래소 체결시각 기준 지연 지표와 알림을 추가했습니다."

### Q4. 이상 탐지 임계값은 어떻게 설정했나요?
> "3단계 반복 조정을 거쳤습니다. 먼저 업비트 감시정책과 학술 논문(EWMA 기반)으로 초기 설계하고, 실시간 데이터로 검증하며 조정했습니다. v1(651건/시간) → v2(72건) → v3(~13건, 31일 실측)으로, 최종적으로 ClickHouse에 적재된 24시간 알림 분포의 percentile 분석으로 p90 기준 임계값을 확정했습니다. RAPID_TRADES는 데이터 분석 결과 Upbit API 전송한계(100건/10초)에 의한 오탐임을 확인하고 비활성화했습니다."

### Q5. 왜 Debezium CDC를 선택했나요? 체결은 CDC가 꼭 필요한가요?
> "솔직히 체결 피드 자체는 실무라면 호가처럼 Kafka에 직접 넣는 게 정답입니다. CDC는 이미 업무용으로 존재하는 DB의 변경을 서비스 코드를 건드리지 않고 뽑을 때 쓰는 기술이고, 이 프로젝트에서는 그 운영 경험을 끝까지 겪어 보려고 체결을 MySQL 경유로 두었습니다. 그 대가로 스키마 변경 추적(온라인 ADD COLUMN을 Debezium이 추적), 대량 삭제 폭주로 인한 46시간 장애, 봉투 오버헤드 +34%, 삭제 부산물이 토픽 트래픽의 84%라는 비용을 전부 실측했고, 그래서 신규 데이터인 호가는 직접 발행으로 갔습니다. 관찰이 끝나면 CDC가 진짜 필요한 자리(가상 매매 원장, 이상탐지 케이스 관리 같은 상태 테이블)로 옮길 계획입니다."

### Q6. Kafka를 3-broker로 구성한 이유는?
> "단일 호스트라 진짜 고가용성은 아닙니다. 디스크가 하나라 내구성 이득도 없습니다. 그래도 복제·ISR·min.insync.replicas·리더 선출·컨슈머 페일오버가 실제로 동작하는 환경이 필요했고, 재생 증폭 실험에서 브로커 1대를 죽였을 때의 동작을 실측하려고 유지합니다. 비용은 브로커 3개 RAM 약 1.9GB와 RF3 디스크 쓰기 3배로 재 두었고, 실험이 끝나면 1브로커 KRaft combined 모드로 축소할 계획입니다."

### Q7. n8n 알림을 왜 추가했나요?
> "탐지만 하고 끝나면 운영 의미가 없습니다. FDS 이상거래는 즉시 Slack + Gmail로 상세 내역을 발송하고, 파이프라인 장애는 별도 채널로 복구 가이드와 함께 알림합니다. 이전 FDS Pipeline Lab 프로젝트에서도 같은 패턴으로 SLA 모니터링을 구축한 경험이 있습니다."

### Q8. RAPID_TRADES를 왜 비활성화했나요?
> "데이터 분석 결과입니다. 24시간 동안 73건이 탐지됐는데 전부 정확히 100건이었고, 101건 이상은 단 한 건도 없었습니다. ClickHouse에서 10초 윈도우 분석을 해보니 BTC도 최대 100건이 천장이었고, 이는 Upbit WebSocket API의 전송 한계였습니다. 이상거래가 아니라 API 제약이므로 비활성화했고, 코드 구조는 유지하여 추후 거래소 내부 데이터 연동 시 재활성화할 수 있도록 했습니다."

### Q9. Airflow를 왜 도입했고, cron 대비 장점은?
> "dbt를 cron으로 돌리면 실패 여부를 알 수 없고, 코인별 품질검증이나 후속 작업 연동이 불가능합니다. Airflow 도입으로 dbt run → dbt test → 코인별 품질검증(Dynamic Task Mapping) → quality gate → Slack 리포트까지 하나의 DAG으로 오케스트레이션하고, 실패 시 자동 재시도 + Slack 알림까지 처리됩니다. Custom Operator(ClickHouseOperator, FlinkHealthOperator)를 직접 개발하여 추가 드라이버 없이 HTTP API로 ClickHouse에 접근합니다."

### Q10. dbt 3계층 모델 구조는 어떤 기준으로 설계했나요?
> "staging(stg_trades)은 원본 정제 VIEW, intermediate(int_ohlcv_1h, int_ohlcv_daily)는 시간/일별 OHLCV 집계 TABLE, marts(mart_daily_summary, mart_volume_spike, mart_alert_rate)는 리포트/대시보드용 최종 테이블입니다. Flink가 실시간 적재한 raw 데이터를 dbt가 배치로 가공하여, 실시간 스트리밍과 배치 분석을 분리합니다."

### Q12. Flink 상태 백엔드를 RocksDB에서 hashmap으로 바꾼 이유는?
> "체크포인트가 625MB였는데 TaskManager 로컬 db 디렉터리를 열어 보니 SST 파일 합계는 15KB이고 352MB짜리 MANIFEST 파일이 크기의 대부분이었습니다. 5마켓×5개 ValueState라 실제 상태는 수십 KB인데 네이티브 풀 체크포인트가 RocksDB의 버전 기록 파일을 매번 통째로 복사한 겁니다. canonical savepoint를 떠 보니 20KB로 확인됐고, hashmap으로 바꾸자 체크포인트 17.8KB, e2e 1.4초→51ms가 됐습니다. 상태가 메모리에 들어가는 규모면 RocksDB의 오버헤드를 낼 이유가 없습니다."

### Q13. 호가는 왜 체결과 다른 경로로 수집하나요?
> "실측 결과 호가는 체결의 14배 건수, 48배 바이트였습니다. MySQL과 binlog를 거치면 하루 13~23GB를 DB에 쓰고 Debezium 봉투로 34%가 더 붙습니다. 호가는 초당 수백 번 갱신되는 스냅샷이라 원장에 남길 이유도 없습니다. 그래서 수집기가 Kafka에 직접 발행하고, 장애 격리 차원에서도 호가 폭주가 체결 적재를 밀어내지 않게 프로세스를 분리했습니다."

### Q14. 왜 7일 동안 아무것도 바꾸지 않고 관찰하나요?
> "10분 확인은 '깨지지 않았다'만 알려 줍니다. 8월 지연 사고도 하루 단위 데이터를 봐야 보였습니다. 피크 시간대(KST 09시, 22~24시)와 주말 저거래 구간을 한 사이클 겪어야 언제 밀리는지 알 수 있어서, 가설 12개와 임계값을 먼저 적어 두고 5분마다 87개 지표를 쌓습니다. 관찰 결과로 튜닝 순서를 정하고, 그 뒤에 같은 실데이터를 배속 재생하는 부하 실험으로 개선 전후를 비교합니다."

### Q11. 알림 체계가 n8n과 Airflow 두 개인 이유는?
> "역할이 다릅니다. n8n은 매분 ClickHouse를 폴링하여 FDS 이상거래와 CDC 장애를 **즉시** Slack + Gmail로 알립니다. Airflow는 매일 01:00 KST에 전날 데이터를 **일일 리포트**로 종합합니다 — CDC 지연 percentile, 코인별 품질검증, 이상탐지 요약, 거래량 급등 등. health_check DAG은 10분 간격으로 파이프라인 컴포넌트 상태를 점검하되, 이상 시에만 알림을 보내 alert fatigue를 방지합니다."

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
| 운영 | 24시간 상시 (2026-02-13 시작, 200일+ 가동 중) |
