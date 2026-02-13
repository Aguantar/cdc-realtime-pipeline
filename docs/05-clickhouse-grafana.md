# Phase 4: ClickHouse 저장소 + Grafana 대시보드

> **기간:** 2025.02.12 ~ 02.13  
> **목표:** Flink 처리 결과를 ClickHouse에 저장하고, Grafana로 실시간 모니터링 대시보드를 구성한다.

---

## 1. 왜 ClickHouse인가

Flink에서 처리된 3종류의 데이터(Raw 이벤트, 윈도우 집계, 이상 탐지 알림)를 저장할 OLAP 데이터베이스가 필요하다. ClickHouse를 선택한 이유:

- **컬럼 지향 저장**: 시계열 집계 쿼리(SUM, AVG, COUNT)가 압도적으로 빠르다. 행 지향 DB(MySQL, PostgreSQL)와 비교해 10~100배 차이.
- **대량 INSERT 최적화**: 배치 INSERT 시 수십만 건/초 처리 가능. CDC 이벤트의 고속 적재에 적합.
- **MergeTree 엔진**: 데이터 정렬 키(ORDER BY)를 기반으로 자동 인덱싱. 별도 인덱스 설계 없이도 빠른 쿼리.
- **Grafana 네이티브 지원**: 공식 ClickHouse 데이터소스 플러그인이 있어 SQL 한 줄로 시각화 가능.
- **경량 운영**: 단일 바이너리로 동작하며, 16GB 서버에서도 768MB~1.2GB로 운영 가능.

PostgreSQL + TimescaleDB도 대안이었지만, ClickHouse의 압축률과 집계 성능이 이 프로젝트의 시계열 분석에 더 적합했다.

---

## 2. 아키텍처

```
Flink Job
  ├─ Raw 이벤트 ──→ JDBC Sink ──→ ClickHouse [raw_orders]
  ├─ 5분 집계   ──→ JDBC Sink ──→ ClickHouse [order_aggregations]
  └─ 이상 탐지  ──→ JDBC Sink ──→ ClickHouse [anomaly_alerts]
                                        │
                                        ▼
                                    Grafana
                                  (11개 패널)
                                        │
                                        ▼
                              Caddy Reverse Proxy
                         (grafana.calmee.store, HTTPS)
```

---

## 3. ClickHouse 테이블 설계

### 3.1 raw_orders — CDC 이벤트 원본

```sql
CREATE TABLE raw_orders (
    op              String,           -- r(snapshot), c(insert), u(update), d(delete)
    order_id        UInt64,
    user_id         UInt64,
    symbol          String,
    order_type      String,           -- BUY / SELL
    quantity        UInt32,
    price           Float64,
    status          String,
    total_amount    Float64,          -- Flink에서 계산 (quantity × price)
    source_ts       DateTime64(3),    -- MySQL 변경 시각
    cdc_ts          DateTime64(3),    -- Debezium 처리 시각
    cdc_latency_ms  Int64,            -- CDC 레이턴시
    flink_ts        DateTime64(3),    -- Flink 처리 시각
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(source_ts)
ORDER BY (symbol, source_ts, order_id)
TTL toDateTime(source_ts) + INTERVAL 90 DAY;
```

ORDER BY를 `(symbol, source_ts, order_id)`로 설정한 이유: Grafana에서 가장 빈번한 쿼리가 "특정 종목의 특정 시간대 주문 조회"이기 때문이다. ClickHouse는 ORDER BY 키 순서대로 데이터를 물리적으로 정렬 저장하므로, `WHERE symbol = '005930' AND source_ts >= ...` 쿼리 시 스캔 범위가 크게 줄어든다.

TTL 90일: 16GB 서버에서 디스크 관리를 위해 설정. 90일이 지난 데이터는 자동 삭제된다.

### 3.2 order_aggregations — 5분 윈도우 집계

```sql
CREATE TABLE order_aggregations (
    symbol          String,
    window_start    DateTime64(3),
    window_end      DateTime64(3),
    order_count     UInt64,
    buy_count       UInt64,
    sell_count      UInt64,
    total_amount    Float64,
    avg_price       Float64,
    min_price       Float64,
    max_price       Float64,
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(window_start)
ORDER BY (symbol, window_start);
```

ORDER BY `(symbol, window_start)`: Grafana 시계열 차트에서 "종목별 시간대별 집계"를 그릴 때 최적화된다.

### 3.3 anomaly_alerts — 이상 탐지 알림

```sql
CREATE TABLE anomaly_alerts (
    alert_type      String,           -- LARGE_ORDER, HIGH_AMOUNT, PRICE_SPIKE, RAPID_ORDERS
    symbol          String,
    order_id        UInt64,
    message         String,
    value           Float64,
    threshold       Float64,
    detected_at     DateTime64(3),
    inserted_at     DateTime64(3) DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(detected_at)
ORDER BY (symbol, detected_at, alert_type);
```

### 3.4 mv_latency_stats — Materialized View

```sql
CREATE MATERIALIZED VIEW mv_latency_stats
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMMDD(minute)
ORDER BY (minute)
AS SELECT
    toStartOfMinute(source_ts) AS minute,
    avgState(cdc_latency_ms)   AS avg_latency,
    maxState(cdc_latency_ms)   AS max_latency,
    minState(cdc_latency_ms)   AS min_latency,
    countState()               AS event_count
FROM raw_orders
WHERE op IN ('c', 'u', 'd')
GROUP BY minute;
```

Materialized View를 사용한 이유: raw_orders에 INSERT될 때 자동으로 분 단위 레이턴시 통계가 집계된다. Grafana에서 레이턴시 추이를 조회할 때 매번 raw 데이터를 전체 스캔하지 않아도 된다.

`avgState()`를 사용한 이유: AggregatingMergeTree는 중간 상태를 저장하고, 조회 시 `avgMerge()`로 최종 결과를 계산한다. 이 방식이 ClickHouse Materialized View에서 집계를 정확하게 유지하는 표준 패턴이다.

---

## 4. Flink ClickHouse JDBC Sink

### 4.1 ClickHouseSinks 팩토리 클래스

```java
public class ClickHouseSinks {

    private static final int BATCH_SIZE = 100;
    private static final long BATCH_INTERVAL_MS = 5000;
    private static final int MAX_RETRIES = 3;

    public static SinkFunction<OrderEvent> rawOrderSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO raw_orders (...) VALUES (?, ?, ...)",
            (ps, event) -> { /* PreparedStatement 바인딩 */ },
            executionOptions(),
            connectionOptions(clickhouseUrl)
        );
    }
}
```

왜 JDBC Sink인가: Flink의 공식 JDBC Connector로, 배치 INSERT를 지원한다. ClickHouse는 건건 INSERT보다 배치 INSERT가 10~100배 빠르므로, `batchSize: 100`으로 100건 모아서 한번에 INSERT한다.

`batchIntervalMs: 5000`: 데이터가 적을 때 100건이 안 모이면 5초마다 모인 만큼 INSERT한다. 실시간성과 성능의 균형점이다.

### 4.2 환경변수로 설정 분리

```java
String clickhouseUrl = System.getenv().getOrDefault(
    "CLICKHOUSE_URL",
    "jdbc:clickhouse://clickhouse:8123/cdc_pipeline"
);
```

Docker 네트워크 내부에서 `clickhouse:8123`으로 HTTP 프로토콜을 통해 접속한다. 호스트에 포트를 노출할 필요가 없다.

---

## 5. Grafana 대시보드

### 5.1 대시보드 구성 (11개 패널)

| 위치 | 패널 | 타입 | 내용 |
|------|------|------|------|
| 상단 Row 1 | Total Orders | stat | 전체 INSERT/UPDATE 주문 수 |
| | Avg CDC Latency | stat | 최근 1시간 평균 레이턴시 (ms) |
| | Active Alerts | stat | 최근 1시간 이상 탐지 건수 |
| | Total Volume (KRW) | stat | 총 주문 금액 (₩) |
| | Symbols Tracked | stat | 추적 중인 종목 수 |
| | Pipeline Status | stat | LIVE / STALE (10분 내 데이터 유무) |
| 중단 Row 2 | Order Volume by Symbol | timeseries | 5분 윈도우별 종목 거래금액 (stacked bar) |
| | CDC Latency (ms) | timeseries | CDC 레이턴시 산점도 (threshold 색상) |
| 하단 Row 3 | Buy vs Sell by Symbol | barchart | 종목별 매수/매도 비율 |
| | Anomaly Alerts | table | 이상 탐지 알림 이력 (색상 코딩) |
| 최하단 Row 4 | Recent Orders (Live) | table | 최근 30건 주문 실시간 테이블 |

### 5.2 JSON 프로비저닝

Grafana 대시보드를 JSON 파일로 프로비저닝하면, 컨테이너를 재생성해도 대시보드가 자동으로 복원된다.

필요한 파일 구조:

```
grafana/provisioning/
├── datasources/
│   └── clickhouse.yml           # ClickHouse 데이터소스 자동 등록
└── dashboards/
    ├── dashboards.yml           # 프로비저닝 설정
    └── json/
        └── cdc-pipeline.json    # 대시보드 정의
```

프로비저닝 설정(dashboards.yml):

```yaml
apiVersion: 1
providers:
  - name: 'CDC Pipeline'
    type: file
    options:
      path: /etc/grafana/provisioning/dashboards/json
```

docker-compose.yml 볼륨:

```yaml
grafana:
  volumes:
    - grafana_data:/var/lib/grafana
    - ./grafana/provisioning/datasources:/etc/grafana/provisioning/datasources
    - ./grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards
```

---

## 6. 트러블슈팅

Phase 4는 ClickHouse 자체보다 **Grafana 접속 경로 구성**에서 가장 많은 시간을 쏟았다.

### 6.1 ClickHouse DB 자동 생성 실패

**증상:**
```
DB::Exception: Database cdc_pipeline does not exist. (UNKNOWN_DATABASE)
```

**원인:** `docker-entrypoint-initdb.d/init.sql`에 `CREATE DATABASE` 문이 없었다. ClickHouse는 `CLICKHOUSE_DB` 환경변수로 기본 DB를 설정할 수 있지만, 이 기능은 특정 이미지 버전에서만 동작한다.

**해결:** init.sql 맨 위에 추가:
```sql
CREATE DATABASE IF NOT EXISTS cdc_pipeline;
USE cdc_pipeline;
```

**주의:** init.sql은 볼륨이 비어있을 때(최초 실행)만 실행된다. 이미 볼륨이 있으면 수동 실행 필요:
```bash
docker exec cdc-clickhouse clickhouse-client \
  --multiquery --queries-file /docker-entrypoint-initdb.d/init.sql
```

### 6.2 ClickHouse Native 포트 충돌

**증상:**
```
failed to bind host port 0.0.0.0:9000/tcp: address already in use
```
ClickHouse 컨테이너가 시작되지 않음.

**원인:** ClickHouse의 Native 프로토콜 포트(9000)가 VS Code Remote Tunnel 프로세스와 충돌했다. `lsof -i :9000`으로 확인하면 Python 프로세스(VS Code)가 이미 점유 중이었다.

**해결:** Native 포트 노출을 제거했다.

```yaml
clickhouse:
  ports:
    - "8123:8123"    # HTTP만 노출
    # 9000 제거 — Grafana/Flink는 Docker 내부 네트워크로 접속
```

Docker 내부 네트워크에서는 `clickhouse:9000`으로 직접 통신하므로, 호스트에 포트를 노출할 필요가 없다. 외부 디버깅이 필요하면 HTTP 포트(8123)를 사용한다.

### 6.3 ClickHouse 메모리 부족

**증상:**
```
Memory limit (total) exceeded: would use 776.25 MiB,
maximum: 691.20 MiB. (MEMORY_LIMIT_EXCEEDED)
```
단순한 `SELECT count()` 쿼리조차 실패.

**원인:** Docker 메모리 제한을 768MB로 설정했는데, ClickHouse 프로세스 자체가 약 700MB를 점유하여 쿼리 실행 메모리가 부족했다.

**해결:** 메모리 제한을 1280MB로 상향.

```yaml
clickhouse:
  deploy:
    resources:
      limits:
        memory: 1280M
```

### 6.4 Flink → ClickHouse JDBC 연결 리셋

**증상:**
```
java.sql.SQLException: Connection reset, server ClickHouseNode
[uri=http://clickhouse:8123/cdc_pipeline]
```
Flink Job이 RESTARTING 상태로 전환.

**원인:** Flink Job이 ClickHouse보다 먼저 시작되어 연결을 시도했지만, ClickHouse가 아직 준비되지 않은 상태였다.

**해결:** Flink의 restart-strategy가 자동으로 처리했다.

```yaml
restart-strategy: fixed-delay
restart-strategy.fixed-delay.attempts: 3
restart-strategy.fixed-delay.delay: 10s
```

ClickHouse가 준비된 후 Flink가 자동 재시도하여 연결에 성공했다. 분산 시스템에서 컴포넌트 간 일시적 연결 실패는 정상이며, 재시도 로직이 있으면 자동으로 복구된다.

### 6.5 Grafana 대시보드 프로비저닝 실패 — JSON 래퍼 문제

**증상:** Grafana가 `starting to provision dashboards` / `finished to provision dashboards` 로그를 출력하지만, `/api/search` 결과가 빈 배열.

**원인:** Grafana API로 POST할 때는 `{"dashboard": {...}}` 래퍼가 필요하지만, 파일 프로비저닝에서는 대시보드 객체를 **직접** 넣어야 한다.

잘못된 형식(API용):
```json
{"dashboard": {"id": null, "uid": "...", "panels": [...]}}
```

올바른 형식(파일 프로비저닝용):
```json
{"id": null, "uid": "...", "panels": [...]}
```

**해결:**
```python
import json
with open('cdc-pipeline.json') as f:
    data = json.load(f)
dashboard = data.get('dashboard', data)
with open('cdc-pipeline.json', 'w') as f:
    json.dump(dashboard, f, indent=2)
```

### 6.6 Grafana 대시보드 프로비저닝 실패 — 데이터소스 UID 불일치

**증상:** 대시보드 JSON은 프로비저닝되었지만, 모든 패널이 "No data" 또는 데이터소스 에러를 표시.

**원인:** 대시보드 JSON에서 데이터소스를 `"uid": "clickhouse"`로 참조했지만, Grafana가 자동 생성한 데이터소스의 실제 UID는 `PDEE91DDB90597936`이었다.

```bash
# 실제 데이터소스 UID 확인
curl -s -u admin:cdc_grafana_2025 http://localhost:3000/api/datasources
# → "uid": "PDEE91DDB90597936"
```

**해결:**
```bash
# 대시보드 JSON의 uid를 실제 값으로 교체
sed -i 's/"uid": "clickhouse"/"uid": "PDEE91DDB90597936"/g' cdc-pipeline.json

# datasource YAML에도 고정 uid 추가
# clickhouse.yml:
#   uid: PDEE91DDB90597936
```

**교훈:** 프로비저닝에서 데이터소스 UID를 명시적으로 고정하지 않으면 Grafana가 랜덤 UID를 생성한다. 대시보드와 데이터소스를 모두 프로비저닝할 때는 YAML에 `uid`를 명시해야 한다.

### 6.7 Grafana 볼륨 마운트 — restart vs recreate

**증상:** docker-compose.yml에 새 볼륨 마운트를 추가한 후 `docker compose restart grafana`를 실행했지만, 컨테이너 안에 새 디렉토리가 보이지 않음.

```bash
docker exec cdc-grafana ls /etc/grafana/provisioning/dashboards/
# → 빈 디렉토리 (json/ 폴더 없음)
```

**원인:** `restart`는 기존 컨테이너를 그대로 재시작한다. docker-compose.yml의 설정 변경(볼륨, 환경변수 등)은 컨테이너를 **재생성(recreate)**해야 반영된다.

**해결:**
```bash
# restart → 설정 변경 반영 안 됨
docker compose restart grafana  # ✗

# up -d → 변경 감지하여 자동 recreate
docker compose up -d grafana    # ✓
```

`docker compose up -d`는 docker-compose.yml의 변경을 감지하여 해당 서비스만 자동으로 재생성한다.

### 6.8 Grafana 외부 접속 — VS Code Proxy 문제

**증상:** `localhost:3000`으로 접속 불가 (ERR_CONNECTION_REFUSED). `https://code.calmee.store/proxy/3000/`으로 접속하면 "Grafana has failed to load its application files" 에러.

**원인:** 이 프로젝트의 미니PC는 VS Code Remote Tunnel을 통해 접속한다. VS Code는 포트를 자동 포워딩할 때 `/proxy/3000/` 서브패스를 사용하는데, Grafana는 이 서브패스를 인식하지 못해 정적 파일(JS, CSS)을 `/public/...` 경로에서 찾으려 했다.

**시도한 방법들:**

| 시도 | 결과 | 원인 |
|------|------|------|
| `localhost:3000` 직접 접속 | ERR_CONNECTION_REFUSED | 미니PC에서 실행 중이라 localhost가 다름 |
| `192.168.219.114:3000` | ERR_CONNECTION_TIMED_OUT | 공유기 방화벽으로 내부 포트 차단 |
| VS Code Proxy `/proxy/3000/` | 정적 파일 로드 실패 | 서브패스 미인식 |
| `GF_SERVER_SERVE_FROM_SUB_PATH=true` | TOO_MANY_REDIRECTS | VS Code Proxy와 Grafana 리다이렉트 충돌 |

**최종 해결: Caddy 리버스 프록시**

이 미니PC에는 이미 Caddy 리버스 프록시가 구동 중이었다. `code.calmee.store`와 `n8n.calmee.store`가 각각 VS Code Server와 n8n으로 라우팅되고 있었다.

같은 방식으로 Grafana 전용 서브도메인을 추가했다:

Step 1: 가비아 DNS에 `grafana` A 레코드 추가 (같은 공인 IP)

Step 2: Caddyfile에 블록 추가:
```
grafana.calmee.store {
    reverse_proxy localhost:3000
}
```

Step 3: Caddy 리로드:
```bash
sudo systemctl reload caddy
```

Caddy가 자동으로 Let's Encrypt SSL 인증서를 발급하여, `https://grafana.calmee.store`로 즉시 HTTPS 접속이 가능해졌다.

**교훈:** VS Code Remote Tunnel의 포트 프록시는 단순한 API 서버에는 잘 동작하지만, SPA(Single Page Application)처럼 정적 리소스를 많이 로드하는 서비스에는 적합하지 않다. 이런 경우 Caddy나 Nginx 같은 전용 리버스 프록시가 훨씬 안정적이다.

---

## 7. 검증 결과

### 7.1 E2E 파이프라인 테스트

MySQL에 5건 INSERT 후 Grafana 대시보드에서 확인:

```sql
INSERT INTO orders_db.orders VALUES
(4001, '005930', 'BUY', 150, 76000.00, 'FILLED'),
(4002, '005930', 'SELL', 600, 76500.00, 'PENDING'),
(4003, '035420', 'BUY', 50, 220000.00, 'FILLED'),
(4004, '000660', 'BUY', 900, 185000.00, 'PENDING'),
(4005, '035720', 'SELL', 300, 460000.00, 'FILLED');
```

| 검증 항목 | 결과 |
|-----------|------|
| raw_orders 테이블 | 5건 즉시 적재 ✅ |
| anomaly_alerts | LARGE_ORDER 2건 + HIGH_AMOUNT 2건 발동 ✅ |
| order_aggregations | 5분 윈도우 집계 정상 ✅ |
| CDC 레이턴시 | 3~4ms ✅ |
| Grafana 시계열 차트 | 실시간 갱신 (10초 auto-refresh) ✅ |
| Grafana 알림 테이블 | 색상 코딩 정상 ✅ |

### 7.2 메모리 사용량

```
컴포넌트              Docker 제한    실제 사용
──────────────────────────────────────────────────
MySQL                 1GB            400MB
Zookeeper             384MB          227MB
Kafka Broker ×3       1280MB × 3    394~454MB × 3
Kafka Connect         1280MB         468MB
Kafka UI              384MB          215MB
Flink JobManager      896MB          337MB
Flink TaskManager     1280MB         356MB
ClickHouse            1280MB         409MB
Grafana               256MB          69MB
──────────────────────────────────────────────────
합계                  ~9.0GB         ~4.1GB
```

16GB 중 약 4.1GB 사용, 여유 약 9.4GB.

---

## 8. 전체 파이프라인 완성

Phase 1~4를 거쳐 완성된 전체 파이프라인:

```
[Data Source]
     │
     ▼
  MySQL (binlog ON, GTID ON)
     │
     ▼
  Debezium CDC Connector
     │  (변경 캡처, JSON 직렬화)
     ▼
  Kafka 3-Broker Cluster
     │  (토픽: cdc.orders_db.orders)
     ▼
  Flink Streaming (Java DataStream API)
     ├─ CdcEventParser: JSON → OrderEvent
     ├─ OrderAggregator: 5분 윈도우 집계
     └─ AnomalyDetector: 이상 탐지 (4가지 규칙)
     │
     ▼  (JDBC Sink, 배치 INSERT)
  ClickHouse (3 테이블 + 1 MV)
     │
     ▼
  Grafana (11개 패널, 10초 자동 갱신)
     │
     ▼
  Caddy (HTTPS, grafana.calmee.store)
```

| 지표 | 달성값 |
|------|--------|
| E2E 레이턴시 (MySQL → ClickHouse) | 3~15ms |
| 이상 탐지 규칙 | 4종 (LARGE_ORDER, HIGH_AMOUNT, PRICE_SPIKE, RAPID_ORDERS) |
| 컨테이너 수 | 11개 |
| 총 메모리 사용 | ~4.1GB / 16GB |
| HTTPS 접근 | grafana.calmee.store (자동 SSL) |

---

## 9. 다음 단계

| 항목 | 상태 | 내용 |
|------|------|------|
| Data Producer | TODO | 실시간 주식 데이터 생성 (시뮬레이터 또는 한투 API) |
| Cloudflare Tunnel 또는 외부 접근 확장 | TODO | 추가 서비스(Flink UI, Kafka UI 등) 외부 노출 |
| 24/7 운영 안정성 | TODO | 서비스 모니터링, 자동 재시작, 디스크 관리 |
