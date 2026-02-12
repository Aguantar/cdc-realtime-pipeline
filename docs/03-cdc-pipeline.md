# Phase 2: CDC 파이프라인

> **기간:** 2025.02.12  
> **목표:** Debezium MySQL Connector를 통해 orders 테이블의 모든 변경(INSERT/UPDATE/DELETE)을 실시간으로 Kafka에 캡처한다.

---

## 1. Debezium이란

Debezium은 데이터베이스의 변경 이벤트를 캡처하여 Kafka로 스트리밍하는 오픈소스 CDC 플랫폼이다. Kafka Connect 위에서 동작하는 **Source Connector**로, 별도의 애플리케이션 코드 없이 설정만으로 CDC 파이프라인을 구축할 수 있다.

### 왜 binlog를 직접 파싱하지 않고 Debezium을 쓰는가

binlog를 직접 읽는 라이브러리(mysql-binlog-connector-java 등)도 있지만, 직접 구현하면 다음을 모두 직접 처리해야 한다:

- binlog 포지션 추적 및 장애 후 재개
- 스키마 변경(ALTER TABLE) 추적
- 초기 스냅샷(기존 데이터 로드)
- Kafka 토픽 자동 생성 및 직렬화

Debezium은 이 모든 것을 내장하고 있어, 설정 파일 하나로 프로덕션 수준의 CDC 파이프라인을 구축할 수 있다.

---

## 2. 아키텍처

```
MySQL (binlog) → Debezium (Kafka Connect) → Kafka Topics
                                              ├── cdc.orders_db.orders
                                              ├── cdc.orders_db.order_executions
                                              ├── cdc (스키마 변경)
                                              └── _schema-history (내부용)
```

Debezium은 Kafka Connect 프레임워크 위에서 실행된다. Kafka Connect는 Kafka 에코시스템의 데이터 입출력 프레임워크로, Connector(무엇을 읽을지)와 Task(실제 작업)로 구성된다.

---

## 3. Kafka Connect 컨테이너 설정

```yaml
kafka-connect:
  image: debezium/connect:2.5
  environment:
    GROUP_ID: cdc-connect-cluster
    BOOTSTRAP_SERVERS: kafka-1:29092,kafka-2:29093,kafka-3:29094
    CONFIG_STORAGE_TOPIC: _connect-configs
    OFFSET_STORAGE_TOPIC: _connect-offsets
    STATUS_STORAGE_TOPIC: _connect-status
    CONFIG_STORAGE_REPLICATION_FACTOR: 3
    OFFSET_STORAGE_REPLICATION_FACTOR: 3
    STATUS_STORAGE_REPLICATION_FACTOR: 3
    HEAP_OPTS: "-Xmx512m -Xms256m"
```

### 핵심 설정 설명

| 설정 | 값 | 왜 이 값인가 |
|------|-----|-------------|
| `GROUP_ID` | `cdc-connect-cluster` | Connect 워커들의 그룹 식별자. 여러 워커를 띄우면 이 ID로 클러스터링 |
| `BOOTSTRAP_SERVERS` | 3개 Broker 모두 지정 | 1대가 죽어도 나머지로 연결 가능 |
| `CONFIG_STORAGE_TOPIC` | `_connect-configs` | Connector 설정을 Kafka 토픽에 저장. 워커가 재시작해도 설정 유지 |
| `OFFSET_STORAGE_TOPIC` | `_connect-offsets` | Debezium이 "binlog 어디까지 읽었는지"를 저장. 장애 후 정확히 이어서 읽기 가능 |
| `STATUS_STORAGE_TOPIC` | `_connect-status` | Connector/Task 상태를 저장 |
| `*_REPLICATION_FACTOR` | `3` | 내부 토픽도 3중 복제하여 Connect 메타데이터 안전 보장 |
| `HEAP_OPTS` | `512m` | 16GB 서버에서 Connect + Debezium 실행에 충분한 메모리 |

### 왜 debezium/connect 이미지인가

`confluentinc/cp-kafka-connect` 이미지에 Debezium 플러그인을 수동 설치할 수도 있지만, `debezium/connect` 이미지는 MySQL, PostgreSQL 등 주요 Connector가 사전 설치되어 있어 바로 사용 가능하다.

### JSON Converter 설정

```yaml
KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
KEY_CONVERTER_SCHEMAS_ENABLE: "false"
VALUE_CONVERTER_SCHEMAS_ENABLE: "false"
```

`SCHEMAS_ENABLE: false`로 설정한 이유: 기본값(true)을 사용하면 모든 메시지에 스키마 정보가 포함되어 메시지 크기가 2~3배로 늘어난다. 이 프로젝트에서는 Flink가 스키마를 자체적으로 관리하므로, Kafka 메시지에서는 스키마를 빼고 순수 JSON 데이터만 전송한다.

---

## 4. MySQL CDC Connector 설정

```json
{
  "name": "mysql-cdc-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",
    "database.hostname": "cdc-mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "***",
    "database.server.id": "184054",
    "topic.prefix": "cdc",
    "database.include.list": "orders_db",
    "table.include.list": "orders_db.orders,orders_db.order_executions",
    "schema.history.internal.kafka.bootstrap.servers": "kafka-1:29092,...",
    "schema.history.internal.kafka.topic": "_schema-history",
    "include.schema.changes": "true",
    "snapshot.mode": "initial",
    "tombstones.on.delete": true,
    "decimal.handling.mode": "string"
  }
}
```

### 핵심 설정 상세 설명

**`database.server.id: 184054`**

MySQL 복제에서 각 슬레이브는 고유한 server-id를 가져야 한다. Debezium은 MySQL 입장에서 복제 슬레이브처럼 동작하므로, MySQL의 server-id(1)와 겹치지 않는 임의의 숫자를 지정한다.

**`topic.prefix: cdc`**

Debezium이 생성하는 Kafka 토픽의 접두사. `{topic.prefix}.{database}.{table}` 형식으로 토픽이 생성된다. 예: `cdc.orders_db.orders`

**`snapshot.mode: initial`**

Connector가 처음 시작할 때 기존 데이터를 어떻게 처리할지 결정한다.

| 모드 | 동작 |
|------|------|
| `initial` (선택) | 첫 시작 시 전체 테이블을 읽어서 Kafka로 전송한 후, 이후 binlog 변경만 추적 |
| `schema_only` | 스키마만 읽고 기존 데이터는 무시. 이후 변경만 추적 |
| `never` | 스냅샷 없이 현재 binlog 위치부터 시작 |

`initial`을 선택한 이유: CDC 파이프라인을 처음 구축할 때 기존 데이터도 다운스트림(Flink, ClickHouse)에 반영되어야 하므로 초기 스냅샷이 필요하다.

**`tombstones.on.delete: true`**

DELETE 이벤트 후 key는 같고 value가 `null`인 tombstone 메시지를 추가로 발행한다. Kafka의 log compaction이 이 tombstone을 보고 해당 key의 이전 메시지를 삭제할 수 있게 해준다.

**`decimal.handling.mode: string`**

MySQL의 DECIMAL 타입을 Kafka 메시지에서 어떻게 표현할지 결정한다.

| 모드 | 출력 | 문제점 |
|------|------|--------|
| `precise` (기본) | Base64 인코딩된 바이트 | 사람이 읽을 수 없음 |
| `double` | 부동소수점 | 정밀도 손실 가능 |
| `string` (선택) | `"71500.00"` | 정밀도 유지 + 가독성 |

주식 가격 데이터는 정밀도가 중요하므로 `string`을 선택했다.

---

## 5. CDC 이벤트 구조

Debezium이 발행하는 메시지는 다음 구조를 가진다:

```json
{
  "before": { ... },     // 변경 전 row (UPDATE/DELETE에서 존재)
  "after":  { ... },     // 변경 후 row (INSERT/UPDATE에서 존재)
  "source": {
    "version": "2.5.4.Final",
    "connector": "mysql",
    "name": "cdc",
    "ts_ms": 1770869153978,   // MySQL에서 변경이 발생한 시각
    "snapshot": "false",
    "db": "orders_db",
    "table": "orders",
    "server_id": 1,
    "gtid": "be6dd82b-...:16",  // GTID
    "file": "mysql-bin.000005",
    "pos": 815               // binlog 위치
  },
  "op": "u",              // 오퍼레이션 타입
  "ts_ms": 1770869153984  // Debezium이 처리한 시각
}
```

### 오퍼레이션 타입 (`op`)

| op 값 | 의미 | before | after |
|--------|------|--------|-------|
| `r` | Read (초기 스냅샷) | null | 전체 row |
| `c` | Create (INSERT) | null | 새 row |
| `u` | Update (UPDATE) | 변경 전 row | 변경 후 row |
| `d` | Delete (DELETE) | 삭제된 row | null |

### 타임스탬프 두 개의 차이

- `source.ts_ms`: MySQL에서 변경이 **실제로 발생**한 시각
- `ts_ms` (최상위): Debezium이 이벤트를 **처리**한 시각

이 두 값의 차이가 CDC 파이프라인의 레이턴시다. 이후 Flink에서 이 값을 기반으로 E2E 레이턴시를 측정한다.

---

## 6. 검증 결과

### 6.1 초기 스냅샷

Connector 등록 즉시 orders 테이블의 기존 3건이 캡처되었다:

```
op:"r", snapshot:"first"  → order_id=1 (삼성전자 BUY 71500원)
op:"r", snapshot:"true"   → order_id=2 (SK하이닉스 SELL 178000원)
op:"r", snapshot:"last"   → order_id=3 (NAVER BUY 215000원)
```

`snapshot` 필드가 `first` → `true` → `last`로 순서가 표시된다. 다운스트림에서 스냅샷 완료 시점을 알 수 있다.

### 6.2 INSERT 캡처

```sql
INSERT INTO orders (user_id, symbol, order_type, quantity, price, status) 
VALUES (1004, '035720', 'BUY', 200, 450000.00, 'PENDING');
```

```
op:"c", before:null, after:{order_id:4, symbol:"035720", price:"450000.00", ...}
```

- `op: "c"` (create) 확인 ✅
- `before: null` → INSERT는 이전 상태가 없음 ✅
- GTID 포함 → binlog 위치 추적 가능 ✅

### 6.3 UPDATE 캡처

```sql
UPDATE orders SET status='FILLED', price=72000.00 WHERE order_id=1;
```

```
op:"u"
before: {price:"71500.00", status:"PENDING", ...}
after:  {price:"72000.00", status:"FILLED", updated_at:1770869153976, ...}
```

- `op: "u"` (update) 확인 ✅
- **before와 after 모두 전체 컬럼 포함** → `binlog-row-image=FULL` 설정 덕분 ✅
- `updated_at`이 자동으로 갱신됨 → `ON UPDATE CURRENT_TIMESTAMP(3)` 동작 확인 ✅
- price 변경 (71500 → 72000)과 status 변경 (PENDING → FILLED) 모두 캡처 ✅

### 6.4 DELETE 캡처

```sql
DELETE FROM orders WHERE order_id=2;
```

```
op:"d", before:{order_id:2, symbol:"000660", ...}, after:null
```

이어서 `null` 메시지(tombstone)가 하나 더 발행되었다.

- `op: "d"` (delete) 확인 ✅
- **before에 삭제 전 전체 데이터 포함** → 다운스트림에서 "무엇이 삭제됐는지" 알 수 있음 ✅
- tombstone 메시지 발행 → `tombstones.on.delete: true` 동작 확인 ✅

### 6.5 최종 메시지 요약

총 7개 메시지가 Kafka 토픽 `cdc.orders_db.orders`에 기록됨:

| # | op | 내용 |
|---|-----|------|
| 1 | `r` (snapshot first) | order_id=1 초기 데이터 |
| 2 | `u` (update) | order_id=1 PENDING→FILLED |
| 3 | `r` (snapshot true) | order_id=2 초기 데이터 |
| 4 | `r` (snapshot last) | order_id=3 초기 데이터 |
| 5 | `c` (create) | order_id=4 신규 INSERT |
| 6 | `d` (delete) | order_id=2 삭제 |
| 7 | - (tombstone) | order_id=2 키의 null 메시지 |

순서가 스냅샷 순이 아닌 이유: Kafka 파티션별로 메시지가 저장되며, order_id를 기반으로 해싱된 파티션에 분배되기 때문에 Consumer가 읽는 순서는 파티션 배치에 따라 달라진다. 같은 order_id의 이벤트는 같은 파티션에 있으므로, **동일 주문 내에서는 순서가 보장**된다.

---

## 7. 자동 생성된 Kafka 토픽

| 토픽 | 용도 |
|------|------|
| `cdc.orders_db.orders` | orders 테이블 CDC 이벤트 |
| `cdc` | 스키마 변경 이벤트 (DDL) |
| `_schema-history` | Debezium 내부용 스키마 히스토리 |
| `_connect-configs` | Kafka Connect 설정 저장 |
| `_connect-offsets` | Kafka Connect 오프셋 저장 (binlog 위치) |
| `_connect-status` | Kafka Connect 상태 저장 |

`cdc.orders_db.order_executions` 토픽이 없는 이유: 해당 테이블에 아직 데이터가 없어서 Debezium이 토픽을 생성하지 않았다. 데이터가 INSERT되는 시점에 자동 생성된다.

---

## 8. 트러블슈팅

### 8.1 Kafka Connect 내부 토픽 NOT_ENOUGH_REPLICAS

**증상:**
```
Error: NOT_ENOUGH_REPLICAS on topic-partition _connect-configs-0
```

Kafka Connect가 Connector 등록 요청에 대해 `Request timed out` 반환.

**원인:**

Kafka Connect가 자동 생성한 내부 토픽(`_connect-configs`, `_connect-offsets`, `_connect-status`)이 **ReplicationFactor: 1**로 만들어졌다. 그런데 Kafka Broker의 전역 설정이 `min.insync.replicas=2`이므로, "복제본이 1개인데 최소 2개가 동기화되어야 한다"는 모순이 발생하여 모든 쓰기가 무한 재시도에 빠졌다.

이 문제가 발생한 이유: `debezium/connect` 이미지가 환경변수 `CONFIG_STORAGE_REPLICATION_FACTOR: 3`을 토픽 자동 생성 시 반영하지 못했다. Debezium 이미지의 환경변수 매핑이 Confluent 이미지와 다르기 때문이다.

**해결:**

내부 토픽을 수동으로 replication factor 3으로 생성:

```bash
docker stop cdc-kafka-connect

# 잘못된 토픽 삭제
docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --delete --topic _connect-configs
docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --delete --topic _connect-offsets
docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --delete --topic _connect-status

# 수동으로 올바른 설정으로 생성
docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --create --topic _connect-configs \
  --partitions 1 --replication-factor 3 --config cleanup.policy=compact

docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --create --topic _connect-offsets \
  --partitions 25 --replication-factor 3 --config cleanup.policy=compact

docker exec -it cdc-kafka-1 kafka-topics --bootstrap-server kafka-1:29092 \
  --create --topic _connect-status \
  --partitions 5 --replication-factor 3 --config cleanup.policy=compact

docker start cdc-kafka-connect
```

**교훈:**

`min.insync.replicas`와 `replication.factor`의 관계를 항상 확인해야 한다. `min.insync.replicas`는 반드시 `replication.factor`보다 작아야 쓰기가 가능하다. 내부 토픽이 자동 생성될 때 의도한 replication factor로 만들어지는지 반드시 확인하는 것이 좋다.

---

## 9. Kafka UI와 Connect 연동

Kafka UI에서 Debezium Connector 상태를 확인할 수 있도록 연동했다:

```yaml
kafka-ui:
  environment:
    KAFKA_CLUSTERS_0_KAFKACONNECT_0_NAME: debezium
    KAFKA_CLUSTERS_0_KAFKACONNECT_0_ADDRESS: http://kafka-connect:8083
```

이를 통해 Kafka UI(http://localhost:8088)에서:
- Connector 상태 (RUNNING/FAILED/PAUSED) 모니터링
- 토픽별 메시지 내용 확인
- Consumer group lag 모니터링

이 기능은 이후 Cloudflare Tunnel로 외부 접근 시 면접관에게 실시간 데이터 흐름을 보여줄 수 있는 관리 화면이 된다.

---

## 10. Phase 2 → Phase 3 연결점

| 준비 항목 | 상태 | Phase 3에서의 용도 |
|-----------|------|-------------------|
| CDC 이벤트 발행 | ✅ | Flink가 Kafka Consumer로 이벤트를 읽음 |
| JSON 포맷 (스키마 없음) | ✅ | Flink JSON 디시리얼라이저로 바로 파싱 가능 |
| before/after 전체 row | ✅ | Flink에서 상태 변경 추적, 집계에 활용 |
| 타임스탬프 2개 | ✅ | E2E 레이턴시 측정 (source.ts_ms vs 처리 시각) |
| 토픽 파티셔닝 | ✅ | order_id 기준으로 파티셔닝되어 Flink 병렬 처리 가능 |

Phase 3 첫 단계: Flink 클러스터 구성 → Kafka Consumer Job 개발 → `cdc.orders_db.orders` 토픽에서 이벤트를 읽어 실시간 집계(5분 윈도우) 처리
