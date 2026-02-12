# Phase 3: Flink 스트리밍 처리

> **기간:** 2025.02.12  
> **목표:** Kafka CDC 토픽에서 실시간으로 이벤트를 읽어 종목별 집계와 이상 탐지를 수행한다.

---

## 1. 왜 Flink인가

CDC 이벤트는 끊임없이 발생하는 스트림 데이터다. 이를 처리하는 프레임워크로 Flink를 선택한 이유:

- **Exactly-Once 보장**: Kafka Consumer offset과 체크포인트를 결합하여 장애 후에도 중복 없이 복구 가능
- **Stateful 처리**: 종목별로 이전 가격, 주문 횟수 등 상태를 유지하며 이상 탐지 가능
- **Window 연산**: 시간 기반 집계(5분 윈도우)를 프레임워크 수준에서 지원
- **현업 표준**: 토스, 카카오, 쿠팡 등 국내 대규모 데이터 팀에서 Flink + Kafka 조합이 표준

Spark Streaming은 마이크로 배치 방식이라 레이턴시가 수 초~수십 초인 반면, Flink는 이벤트 단위 처리로 밀리초 수준 레이턴시가 가능하다.

---

## 2. 아키텍처

```
Kafka (cdc.orders_db.orders)
    │
    ▼
Flink Kafka Source (Consumer)
    │
    ├─→ CdcEventParser (JSON → OrderEvent)
    │       │
    │       ├─→ [Stream 1] 5분 윈도우 집계 (종목별)
    │       │       → stdout (Phase 4에서 ClickHouse Sink)
    │       │
    │       ├─→ [Stream 2] 이상 탐지 (Stateful)
    │       │       → stdout (Phase 4에서 ClickHouse Sink)
    │       │
    │       └─→ [Stream 3] Raw 이벤트 로깅
    │               → stdout (Phase 4에서 ClickHouse Sink)
```

하나의 Kafka Source에서 읽은 스트림을 3개 방향으로 분기(fan-out)한다. Flink는 이를 하나의 DAG(방향 비순환 그래프)로 최적화하여 Kafka에서 한 번만 읽고 3곳으로 전달한다.

---

## 3. 왜 Java인가

Flink Job 개발 언어로 Java를 선택한 이유:

| 언어 | 장점 | 단점 |
|------|------|------|
| Java (선택) | 현업 표준, DataStream API 완전 지원, 성능 최적 | 보일러플레이트 코드 |
| Flink SQL | 빠른 구현, 선언적 | 복잡한 이상 탐지 로직 한계 |
| PyFlink | Python 친화적 | 성능 이슈, 생태계 미성숙, 현업 사용 드묾 |

이 프로젝트의 이상 탐지 로직(가격 급변, 단기 다수 주문)은 **Keyed State**를 사용하는 `KeyedProcessFunction`이 필요하다. 이는 Java DataStream API에서만 완전히 지원된다.

---

## 4. Flink 클러스터 구성

### 4.1 JobManager + TaskManager

```yaml
flink-jobmanager:
  image: flink:1.18-java11
  command: jobmanager
  environment:
    FLINK_PROPERTIES: |
      jobmanager.rpc.address: flink-jobmanager
      jobmanager.memory.process.size: 768m
      state.backend: rocksdb
      execution.checkpointing.interval: 60000
      execution.checkpointing.mode: EXACTLY_ONCE

flink-taskmanager:
  image: flink:1.18-java11
  command: taskmanager
  environment:
    FLINK_PROPERTIES: |
      taskmanager.memory.process.size: 1g
      taskmanager.numberOfTaskSlots: 2
```

| 컴포넌트 | 역할 | 메모리 |
|----------|------|--------|
| JobManager | Job 스케줄링, 체크포인트 조정, Web UI (8081) | 768MB |
| TaskManager | 실제 데이터 처리 실행, 2개 슬롯 | 1GB |

슬롯을 2개로 설정한 이유: 이 Job은 6개 태스크로 구성되며, 병렬도(parallelism)는 2다. 슬롯 2개면 하나의 TaskManager에서 처리 가능하다. 16GB 제약에서 TaskManager를 여러 대 띄우는 것보다 효율적이다.

### 4.2 State Backend: RocksDB

```yaml
state.backend: rocksdb
state.checkpoints.dir: file:///opt/flink/checkpoints
state.savepoints.dir: file:///opt/flink/savepoints
execution.checkpointing.interval: 60000
execution.checkpointing.mode: EXACTLY_ONCE
```

RocksDB를 선택한 이유:
- 상태(state)를 디스크에 저장하므로 메모리 사용량이 안정적
- 기본 HashMapStateBackend는 모든 상태를 JVM 힙에 저장 → 16GB 제약에서 OOM 위험
- 프로덕션 환경에서도 RocksDB가 표준

체크포인트 60초 간격: 장애 시 최대 60초분의 데이터를 재처리한다. 이 프로젝트 규모에서는 충분하다.

### 4.3 Docker 멀티스테이지 빌드

호스트(미니PC)에 Java나 Maven을 설치하지 않고, Docker 안에서 빌드한다:

```dockerfile
# Stage 1: Maven + JDK 이미지에서 빌드
FROM maven:3.9-eclipse-temurin-11 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B    # 의존성 캐시
COPY src/ src/
RUN mvn clean package -DskipTests -B

# Stage 2: JAR만 추출
FROM alpine:3.19 AS output
COPY --from=builder /build/target/flink-cdc-job-1.0.0.jar /output/
```

`mvn dependency:go-offline`을 먼저 실행하는 이유: Docker 레이어 캐시를 활용하기 위해서다. `pom.xml`이 바뀌지 않으면 의존성 다운로드를 건너뛰고 소스 코드만 재컴파일한다. 빌드 시간이 3분에서 30초로 줄어든다.

빌드된 JAR(42MB)은 `flink/target/` 디렉토리에 저장되고, Docker 볼륨 마운트(`./flink/target:/opt/flink/usrlib`)를 통해 Flink 컨테이너에서 접근한다.

---

## 5. Flink Job 구조

### 5.1 프로젝트 구조

```
flink/
├── Dockerfile                    # 멀티스테이지 빌드
├── pom.xml                       # Maven 의존성 관리
├── target/
│   └── flink-cdc-job-1.0.0.jar  # Fat JAR (42MB)
└── src/main/java/com/cdc/pipeline/
    ├── CdcPipelineJob.java       # 메인 진입점 (DAG 정의)
    ├── model/
    │   ├── OrderEvent.java       # CDC 이벤트 모델
    │   ├── OrderAggResult.java   # 윈도우 집계 결과
    │   └── AnomalyAlert.java     # 이상 탐지 알림
    └── function/
        ├── CdcEventParser.java   # JSON 파싱 (FlatMapFunction)
        ├── OrderAggregator.java  # 5분 집계 (AggregateFunction)
        └── AnomalyDetector.java  # 이상 탐지 (KeyedProcessFunction)
```

### 5.2 Kafka Source 설정

```java
KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
    .setBootstrapServers("kafka-1:29092,kafka-2:29093,kafka-3:29094")
    .setTopics("cdc.orders_db.orders")
    .setGroupId("flink-cdc-consumer")
    .setStartingOffsets(OffsetsInitializer.earliest())
    .setValueOnlyDeserializer(new SimpleStringSchema())
    .build();
```

`OffsetsInitializer.earliest()`를 사용한 이유: Job이 처음 시작할 때 토픽의 가장 처음부터 읽어야 Debezium의 초기 스냅샷 데이터를 놓치지 않는다. 이후 재시작 시에는 체크포인트에 저장된 offset부터 이어서 읽는다.

### 5.3 CdcEventParser — JSON 파싱

`FlatMapFunction`을 사용한 이유:

- tombstone 메시지(value=null)는 건너뛰어야 함 → 0개 출력
- 파싱 실패 시 스트림을 멈추지 않고 건너뛰어야 함 → 0개 출력
- 정상 이벤트 → 1개 출력
- `map()`은 항상 1:1 변환만 가능하지만, `flatMap()`은 0개 이상 출력 가능

파싱 로직의 핵심:

```java
// DELETE는 before에서, 나머지는 after에서 데이터 추출
if ("d".equals(op)) {
    data = root.path("before");
} else {
    data = root.path("after");
}
```

Debezium CDC 이벤트의 `before`/`after` 구조를 정확히 반영한다. DELETE 이벤트는 `after`가 null이므로 `before`에서 삭제 전 데이터를 가져온다.

`decimal.handling.mode=string`으로 설정했으므로 price는 문자열로 온다:

```java
String priceStr = data.path("price").asText("0");
event.setPrice(Double.parseDouble(priceStr));
```

### 5.4 OrderAggregator — 5분 윈도우 집계

```java
orderEvents
    .keyBy(OrderEvent::getSymbol)             // 종목별 그룹핑
    .window(TumblingProcessingTimeWindows.of(Time.minutes(5)))  // 5분 텀블링 윈도우
    .aggregate(new OrderAggregator())         // incremental 집계
```

`AggregateFunction`을 선택한 이유:

Flink 윈도우 집계에는 3가지 방식이 있다:

| 방식 | 메모리 사용 | 설명 |
|------|------------|------|
| `reduce()` | 낮음 | 입출력 타입이 같아야 함 |
| `aggregate()` (선택) | 낮음 | 입출력 타입 다를 수 있음, incremental |
| `apply()` / `process()` | 높음 | 윈도우 내 전체 이벤트를 메모리에 보관 |

`aggregate()`는 이벤트가 도착할 때마다 `add()`로 누적하고, 윈도우가 닫힐 때 `getResult()`로 최종 결과를 출력한다. 윈도우 내 모든 이벤트를 메모리에 저장하지 않으므로 16GB 제약 환경에서 중요한 최적화 포인트다.

집계 항목:
- 주문 건수 (전체, 매수, 매도)
- 총 주문 금액
- 평균/최저/최고 가격

### 5.5 AnomalyDetector — 이상 탐지

```java
orderEvents
    .keyBy(OrderEvent::getSymbol)
    .process(new AnomalyDetector())
```

`KeyedProcessFunction`을 사용한 이유: 종목별로 독립적인 **상태(state)**를 유지해야 하기 때문이다. "삼성전자의 이전 가격"과 "SK하이닉스의 이전 가격"은 별도로 관리되어야 한다.

Flink의 Keyed State는 RocksDB에 저장되므로, 체크포인트 시 자동으로 영속화되어 장애 후에도 상태가 복구된다.

**탐지 규칙 4가지:**

| 규칙 | 조건 | 상태 사용 |
|------|------|----------|
| LARGE_ORDER | 수량 > 500주 | 없음 (단건 판단) |
| HIGH_AMOUNT | 주문 금액 > 1억원 | 없음 (단건 판단) |
| PRICE_SPIKE | 이전 가격 대비 5% 이상 변동 | `ValueState<Double> lastPrice` |
| RAPID_ORDERS | 10초 내 동일 종목 5건 이상 | `ValueState<Long> orderCount`, `windowStart` |

PRICE_SPIKE와 RAPID_ORDERS는 이전 이벤트의 정보가 필요하므로 Keyed State에 저장한다. 이것이 일반 `map()`이나 `filter()`로는 구현할 수 없고 `KeyedProcessFunction`이 필요한 이유다.

---

## 6. 검증 결과

### 6.1 Flink 클러스터

```json
{
    "taskmanagers": 1,
    "slots-total": 2,
    "slots-available": 0,   // Job이 2개 슬롯 모두 사용 중
    "jobs-running": 1,
    "flink-version": "1.18.1"
}
```

6개 태스크가 2개 슬롯에서 실행. Flink Web UI(http://localhost:8081)에서 DAG 시각화 확인 가능.

### 6.2 초기 스냅샷 처리

Flink Job이 시작되자마자 Debezium 스냅샷 이벤트를 처리:

```
RAW:2> OrderEvent{op=r, orderId=1, symbol=005930, BUY 100@72000, status=FILLED, latency=652ms}
RAW:2> OrderEvent{op=r, orderId=3, symbol=035420, BUY 30@215000, status=FILLED, latency=654ms}
RAW:2> OrderEvent{op=r, orderId=4, symbol=035720, BUY 200@450000, status=PENDING, latency=655ms}
```

orderId=2가 없는 이유: Phase 2에서 DELETE 테스트로 삭제했기 때문. 스냅샷은 현재 테이블 상태를 반영하므로 정상.

### 6.3 실시간 CDC 캡처

MySQL에 INSERT → Flink에서 즉시 처리:

```
RAW:1> OrderEvent{op=c, orderId=5, symbol=005930, BUY 50@73000, status=PENDING, latency=15ms}
RAW:1> OrderEvent{op=c, orderId=6, symbol=005930, SELL 1000@74000, status=PENDING, latency=4ms}
RAW:2> OrderEvent{op=c, orderId=7, symbol=000660, BUY 800@180000, status=PENDING, latency=2ms}
```

**CDC 레이턴시: 2~15ms.** MySQL에서 변경이 발생한 시각(`source.ts_ms`)과 Debezium이 처리한 시각(`ts_ms`)의 차이다. 밀리초 수준의 레이턴시는 프로덕션급 성능이다.

### 6.4 이상 탐지 발동

```
ALERT:2> ALERT[LARGE_ORDER] 005930 orderId=6: 대량 주문 감지: 1000주 (임계값: 500) at 2026-02-12 13:57:38
ALERT:1> ALERT[LARGE_ORDER] 000660 orderId=7: 대량 주문 감지: 800주 (임계값: 500) at 2026-02-12 13:57:38
ALERT:1> ALERT[HIGH_AMOUNT] 000660 orderId=7: 고액 주문 감지: 144000000원 (임계값: 100000000) at 2026-02-12 13:57:38
```

| 알림 | 종목 | 값 | 임계값 | 판정 |
|------|------|-----|--------|------|
| LARGE_ORDER | 005930 | 1,000주 | 500주 | ✅ |
| LARGE_ORDER | 000660 | 800주 | 500주 | ✅ |
| HIGH_AMOUNT | 000660 | 1.44억원 | 1억원 | ✅ |

orderId=7(SK하이닉스 800주 × 180,000원)은 수량과 금액 모두 임계값을 초과하여 2개의 알림이 동시에 발생했다.

### 6.5 5분 윈도우 집계

```
AGG:2> Agg{symbol=005930, window=[13:57:02~14:00:00], orders=3(B:2/S:1), total=84850000, avg=73000, range=[72000~74000]}
AGG:2> Agg{symbol=035420, window=[13:57:02~14:00:00], orders=1(B:1/S:0), total=6450000, avg=215000, range=[215000~215000]}
AGG:1> Agg{symbol=035720, window=[13:57:02~14:00:00], orders=1(B:1/S:0), total=90000000, avg=450000, range=[450000~450000]}
AGG:1> Agg{symbol=000660, window=[13:57:38~14:00:00], orders=1(B:1/S:0), total=144000000, avg=180000, range=[180000~180000]}
```

005930(삼성전자) 집계 검증:

- 주문 3건: orderId=1(BUY 72,000), orderId=5(BUY 73,000), orderId=6(SELL 74,000)
- 총 금액: 100×72,000 + 50×73,000 + 1,000×74,000 = 7,200,000 + 3,650,000 + 74,000,000 = 84,850,000 ✅
- 가격 범위: 72,000 ~ 74,000 ✅
- 매수 2건, 매도 1건 ✅

### 6.6 메모리 사용량

```
컴포넌트              Docker 제한    실제 사용
──────────────────────────────────────────────────
MySQL                 1GB            400MB
Zookeeper             384MB          85MB
Kafka Broker ×3       768MB × 3     358~372MB × 3
Kafka Connect         768MB          452MB
Kafka UI              384MB          187MB
Flink JobManager      896MB          294MB
Flink TaskManager     1280MB         334MB
──────────────────────────────────────────────────
합계                  ~6.8GB         ~2.84GB
```

Phase 1(1.58GB) → Phase 3(2.84GB)로 약 1.26GB 증가. 16GB 중 여전히 13GB 이상 여유.

---

## 7. 트러블슈팅

Phase 3은 여러 컴포넌트가 복합적으로 얽히면서 가장 많은 문제를 만났다. 모든 문제와 해결 과정을 기록한다.

### 7.1 JobManager 메모리 설정 충돌

**증상:**
```
IllegalConfigurationException: Inconsistently configured
'jobmanager.memory.jvm-overhead.fraction' (0.1) and its
min (192.000mb), max (128.000mb) value
```
JobManager가 시작 즉시 종료되며 Restarting 루프.

**원인 분석:**

처음에 `jobmanager.memory.process.size: 512m`과 `jvm-overhead.max: 128m`으로 설정했다. Flink의 JVM overhead는 process size의 일정 비율(기본 10%)로 계산되는데:

- process.size = 512MB
- JVM overhead 기본 fraction = 10% → 51.2MB
- JVM overhead 기본 min = 192MB
- 설정한 max = 128MB

`min(192MB) > max(128MB)` → 논리적 모순이 발생하여 Flink가 시작을 거부한 것이다.

**해결:**

`jvm-overhead.max` 설정을 제거하고, `process.size`를 768MB로 올렸다. 768MB × 10% = 76.8MB로, 기본 min(192MB)보다 작지만, Flink가 자동으로 min 값을 사용하므로 모순이 발생하지 않는다.

**교훈:**

Flink의 메모리 모델은 여러 영역(JVM Heap, Off-Heap, Metaspace, Overhead)이 서로 연관되어 있다. 하나만 수동 설정하면 다른 값과 충돌할 수 있다. `process.size`만 설정하고 나머지는 Flink가 자동 계산하게 두는 것이 안전하다.

### 7.2 체크포인트 디렉토리 권한 오류

**증상:**
```
IOException: Failed to create directory for shared state:
file:/opt/flink/checkpoints/.../shared
```
Job이 제출되자마자 FAILED 상태.

**원인 분석:**

Docker 볼륨(`flink_checkpoints`)이 생성될 때 소유자가 `root:root`로 설정된다. 그런데 Flink 컨테이너는 기본적으로 `flink` 유저(UID 9999)로 실행되므로, root 소유의 볼륨에 디렉토리를 생성할 권한이 없었다.

```
drwxr-xr-x  2 root  root  4096  checkpoints   ← root 소유, flink 쓰기 불가
```

**해결:**

docker-compose.yml에서 Flink 컨테이너를 `user: "0"`(root)으로 실행하도록 변경했다.

```yaml
flink-jobmanager:
  user: "0"

flink-taskmanager:
  user: "0"
```

프로덕션에서는 보안상 initContainer나 볼륨 권한 설정으로 해결하지만, 단일 서버 개발 환경에서는 root 실행이 간편하고 문제없다.

**교훈:**

Docker 볼륨의 소유권은 "누가 먼저 생성했는지"에 따라 결정된다. 컨테이너의 실행 유저와 볼륨 소유자가 일치하는지 반드시 확인해야 한다.

### 7.3 docker compose down 후 전면 장애

**증상:**

JobManager 메모리 문제를 수정한 후 `docker compose down` → `docker compose up -d`를 실행했더니, Kafka Broker, Kafka Connect, Flink가 모두 동시에 장애에 빠졌다.

1. Kafka Broker: Cluster ID 불일치로 재시작 루프
2. Kafka Connect: Broker에 접속 불가로 재시작 루프
3. Flink TaskManager: JobManager에 DNS 해석 실패

**원인 분석:**

`docker compose down`은 컨테이너 + 네트워크를 모두 삭제한다. 이때:

1. **Zookeeper 볼륨이 없었다.** Phase 1에서 Zookeeper에 명시적 볼륨을 지정하지 않았기 때문에, `down` 시 Zookeeper 데이터가 사라지고 `up` 시 새로운 Cluster ID가 생성되었다. 그런데 Kafka 볼륨은 남아있어서 이전 Cluster ID를 가지고 있었다 → 불일치.

2. **컨테이너가 거의 동시에 시작되었다.** `depends_on`은 컨테이너 시작 순서만 보장하지, "Kafka가 실제로 요청을 받을 준비가 됐는지"는 확인하지 않는다. Connect가 아직 초기화 중인 Kafka에 접속 시도 → 실패 → 재시작 루프.

3. **네트워크 재생성.** `down`이 Docker 네트워크까지 삭제하므로, `up` 시 새 네트워크가 생성되며 DNS 해석이 일시적으로 실패한다.

**해결 (3단계):**

Step 1: Kafka 볼륨 삭제 후 재시작 (Cluster ID 불일치 해소)
```bash
docker compose down
docker volume rm cdc-realtime-pipeline_kafka{1,2,3}_data
docker compose up -d
```

Step 2: Connect 내부 토픽 수동 생성 (replication factor 3)
```bash
docker stop cdc-kafka-connect
docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --create --topic _connect-configs --partitions 1 --replication-factor 3 --config cleanup.policy=compact
# _connect-offsets, _connect-status도 동일
docker start cdc-kafka-connect
```

Step 3: Debezium Connector 재등록 + Flink Job 재제출

**근본적 해결 (이후 적용):**

| 문제 | 해결 |
|------|------|
| Zookeeper 볼륨 없음 | `zookeeper_data`, `zookeeper_log` 볼륨 추가 |
| 시작 순서 미보장 | Kafka Broker에 `healthcheck` 추가, Connect에 `condition: service_healthy` |
| Connect 토픽 RF=1 | `scripts/startup.sh`에서 자동 확인/수정 |
| 체크포인트 권한 | Flink를 `user: "0"`으로 실행 |

### 7.4 Connect 내부 토픽 replication factor 반복 문제

**증상:**

`docker compose down` → `up` 후 Kafka Connect가 `NOT_ENOUGH_REPLICAS` 에러를 무한 반복.

```
WARN Got error produce response on topic-partition _connect-configs-0,
retrying. Error: NOT_ENOUGH_REPLICAS
```

**원인 분석:**

이 문제는 Phase 2에서도 발생했고, Phase 3에서 2번 더 발생했다.

Debezium 이미지(`debezium/connect:2.5`)는 환경변수 `CONFIG_STORAGE_REPLICATION_FACTOR: 3`을 토픽 자동 생성 시 반영하지 못한다. 토픽이 `ReplicationFactor: 1`로 생성되는데, Kafka 전역 설정이 `min.insync.replicas=2`이므로:

- 복제본 1개인데 최소 2개가 동기화되어야 함 → 모순
- 모든 쓰기가 영원히 실패

**해결:**

`scripts/startup.sh` 스크립트를 만들어 자동화했다:

```bash
# 토픽의 replication factor를 확인하고, 1이면 삭제 후 3으로 재생성
fix_topic() {
    local TOPIC=$1
    local PARTITIONS=$2
    RF=$(docker exec cdc-kafka-1 kafka-topics ... --describe --topic "$TOPIC" | grep -o "ReplicationFactor: [0-9]*" | awk '{print $2}')
    if [ "$RF" = "1" ]; then
        docker stop cdc-kafka-connect
        docker exec cdc-kafka-1 kafka-topics ... --delete --topic "$TOPIC"
        docker exec cdc-kafka-1 kafka-topics ... --create --topic "$TOPIC" --partitions "$PARTITIONS" --replication-factor 3
    fi
}
fix_topic "_connect-configs" 1
fix_topic "_connect-offsets" 25
fix_topic "_connect-status" 5
```

**교훈:**

`min.insync.replicas`와 `replication.factor`의 관계는 반드시 `replication.factor > min.insync.replicas`여야 한다. 서드파티 이미지가 환경변수를 기대대로 반영하는지 반드시 검증해야 한다.

### 7.5 TaskManager → JobManager DNS 해석 실패

**증상:**
```
UnknownHostException: flink-jobmanager: Temporary failure in name resolution
Could not resolve ResourceManager address, retrying in 10000 ms
```
TaskManager가 10초마다 재시도하는 로그가 반복.

**원인 분석:**

`docker compose down`이 네트워크를 삭제하고 `up`이 새로 생성하면서, Docker의 내장 DNS가 즉시 모든 컨테이너 이름을 해석하지 못하는 과도기가 발생한다. TaskManager가 JobManager보다 먼저 시작되면 이 문제가 나타난다.

**해결:**

이 문제는 **자동으로 복구**된다. Flink TaskManager는 내장 재시도 로직이 있어 10초마다 재시도하며, JobManager가 준비되면 자동으로 등록에 성공한다.

```
INFO Resolved ResourceManager address, beginning registration
INFO Successful registration at resource manager
```

**교훈:**

분산 시스템에서 컴포넌트 간 일시적 접속 실패는 정상이다. 중요한 것은 재시도 로직이 있는가, 그리고 결국 복구되는가이다. 로그 레벨이 WARN이라고 해서 항상 문제인 것은 아니다.

---

## 8. 개선: 안정적인 시작 순서 보장

Phase 3의 트러블슈팅 경험을 반영하여, 두 가지 개선을 적용했다.

### 8.1 docker-compose.yml — healthcheck + Zookeeper 볼륨

```yaml
# Kafka Broker에 healthcheck 추가
kafka-1:
  healthcheck:
    test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "kafka-1:29092"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s

# Connect는 Broker가 healthy일 때만 시작
kafka-connect:
  depends_on:
    kafka-1:
      condition: service_healthy
    kafka-2:
      condition: service_healthy
    kafka-3:
      condition: service_healthy

# Zookeeper에 명시적 볼륨 추가 (Cluster ID 보존)
zookeeper:
  volumes:
    - zookeeper_data:/var/lib/zookeeper/data
    - zookeeper_log:/var/lib/zookeeper/log
```

이제 `docker compose down` → `up` 시:
1. Zookeeper가 이전 Cluster ID를 보존 → Kafka Broker 정상 시작
2. Kafka Broker가 healthy 상태가 된 후에 Connect 시작
3. Connect 내부 토픽 문제 발생 확률 감소

### 8.2 scripts/startup.sh — 원커맨드 파이프라인 시작

```bash
docker compose up -d
bash scripts/startup.sh
```

이 두 줄로 전체 파이프라인이 올라간다. startup.sh가 하는 일:

1. Kafka Broker 대기 (최대 60초)
2. Connect 내부 토픽 RF 확인/수정 (RF=1이면 삭제 후 RF=3으로 재생성)
3. Kafka Connect 대기 (최대 90초)
4. Debezium Connector 등록 (이미 있으면 건너뜀)
5. Flink Job 제출 (이미 실행 중이면 건너뜀)

---

## 9. Phase 3 → Phase 4 연결점

| 준비 항목 | 상태 | Phase 4에서의 용도 |
|-----------|------|-------------------|
| 5분 윈도우 집계 | ✅ stdout 출력 | ClickHouse 테이블에 INSERT |
| 이상 탐지 알림 | ✅ stdout 출력 | ClickHouse 알림 테이블 + Grafana 알림 |
| Raw 이벤트 | ✅ stdout 출력 | ClickHouse 상세 이력 테이블 |
| E2E 레이턴시 측정 | ✅ 2~15ms 확인 | Grafana 대시보드 지표 |
| Flink JDBC Connector | ✅ pom.xml에 포함 | ClickHouse JDBC Sink |
| ClickHouse JDBC Driver | ✅ pom.xml에 포함 | ClickHouse 접속 |

Phase 4 첫 단계: ClickHouse 컨테이너 추가 → 테이블 생성 → Flink Job에 JDBC Sink 추가 → Grafana 대시보드 구성
