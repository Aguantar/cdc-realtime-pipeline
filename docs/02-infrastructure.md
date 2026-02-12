# Phase 1: 인프라 구축

> **기간:** 2025.02.12  
> **목표:** MySQL(CDC Source) + Kafka 3-Broker 클러스터를 16GB 메모리 제약 안에서 구축하고, 안정성을 검증한다.

---

## 1. 서버 환경

| 항목 | 스펙 |
|------|------|
| 하드웨어 | Mini PC (On-Premise) |
| OS | Ubuntu 24.04 LTS |
| CPU | 4코어 |
| RAM | 16GB |
| Disk | 500GB SSD |
| Docker | 29.2.0 |
| Docker Compose | v5.0.2 |

클라우드가 아닌 물리 서버를 선택한 이유는, On-Premise 환경에서 직접 리소스를 관리하고 제약 속에서 최적화하는 경험을 쌓기 위해서다. AWS MSK나 Confluent Cloud를 쓰면 편하지만, 그 안에서 실제로 어떤 일이 벌어지는지는 알 수 없다.

---

## 2. MySQL — CDC Source Database

### 2.1 왜 MySQL 8.0인가

Debezium CDC는 MySQL의 **binlog(Binary Log)** 를 읽어서 변경 이벤트를 캡처한다. binlog는 MySQL이 모든 데이터 변경을 순서대로 기록하는 파일로, 원래는 복제(Replication)를 위해 만들어졌지만 CDC에서도 핵심 역할을 한다.

MySQL 8.0을 선택한 이유:
- Debezium이 공식 지원하는 안정 버전
- GTID(Global Transaction ID) 지원 → Debezium이 정확한 위치에서 읽기 재개 가능
- ROW 기반 binlog가 기본 지원

### 2.2 binlog 설정과 그 이유

```
--binlog-format=ROW
--binlog-row-image=FULL
--gtid-mode=ON
--enforce-gtid-consistency=ON
```

| 설정 | 값 | 왜 이 값인가 |
|------|-----|-------------|
| `binlog-format` | `ROW` | **Debezium 필수 조건.** STATEMENT 형식은 SQL문만 기록하지만, ROW는 실제 변경된 행의 데이터를 기록한다. CDC는 "어떤 데이터가 어떻게 바뀌었는지"를 알아야 하므로 ROW가 필수다. |
| `binlog-row-image` | `FULL` | UPDATE 시 변경 전(before)과 변경 후(after) **전체 컬럼**을 기록한다. MINIMAL로 하면 변경된 컬럼만 기록되어 디스크는 아끼지만, 다운스트림에서 전체 row를 복원할 수 없다. |
| `gtid-mode` | `ON` | 각 트랜잭션에 전역 고유 ID를 부여한다. Debezium이 장애 후 "어디까지 읽었는지"를 정확히 알 수 있어 **exactly-once에 가까운 전달**이 가능해진다. |
| `expire-logs-days` | `3` | binlog 파일을 3일간 보관 후 삭제. 500GB SSD에서 장기 운영 시 디스크 부족을 방지한다. |

### 2.3 검증 결과

```
+------------------+-------+
| Variable_name    | Value |
+------------------+-------+
| log_bin          | ON    |
| binlog_format    | ROW   |
| binlog_row_image | FULL  |
| gtid_mode        | ON    |
+------------------+-------+
```

### 2.4 스키마 설계

주식 주문 시스템을 모델링했다. CDC가 캡처할 대상 테이블이다.

**orders 테이블** — 주문 생성/수정/취소

| 컬럼 | 타입 | 설명 |
|------|------|------|
| order_id | BIGINT PK | 자동 증가 |
| user_id | BIGINT | 주문자 |
| symbol | VARCHAR(20) | 종목코드 (예: 005930 = 삼성전자) |
| order_type | ENUM('BUY','SELL') | 매수/매도 |
| quantity | INT | 수량 |
| price | DECIMAL(15,2) | 주문 가격 |
| status | ENUM('PENDING','FILLED','CANCELLED','PARTIAL') | 주문 상태 |
| created_at | DATETIME(3) | 밀리초 단위 생성 시각 |
| updated_at | DATETIME(3) | 밀리초 단위 수정 시각 (ON UPDATE 자동) |

`DATETIME(3)`을 사용한 이유: 밀리초 단위까지 기록해야 E2E 레이턴시 측정이 정확하다.

`updated_at`에 `ON UPDATE CURRENT_TIMESTAMP(3)`을 건 이유: 주문 상태가 PENDING → FILLED로 바뀔 때 Debezium이 이 변경을 캡처하고, 정확한 변경 시각을 기록하기 위해서다.

**order_executions 테이블** — 체결 이력

하나의 주문(order)이 여러 번에 걸쳐 체결(partial fill)될 수 있으므로 분리했다.

### 2.5 CDC 전용 유저

```sql
CREATE USER 'debezium'@'%' IDENTIFIED BY '***';
GRANT SELECT, RELOAD, SHOW DATABASES, 
      REPLICATION SLAVE, REPLICATION CLIENT 
ON *.* TO 'debezium'@'%';
```

| 권한 | 왜 필요한가 |
|------|------------|
| `REPLICATION SLAVE` | binlog 이벤트를 읽기 위해 |
| `REPLICATION CLIENT` | `SHOW MASTER STATUS` 등 복제 상태 조회 |
| `SELECT` | 초기 스냅샷(snapshot) 시 테이블 전체 읽기 |
| `RELOAD` | 테이블 락 획득 (스냅샷 일관성 보장) |

root 계정을 사용하지 않는 이유: 최소 권한 원칙(Principle of Least Privilege). 프로덕션에서는 CDC 전용 계정을 분리하는 것이 보안의 기본이다.

### 2.6 메모리 튜닝

```ini
[mysqld]
innodb_buffer_pool_size = 512M
innodb_log_file_size = 64M
max_connections = 100
```

Docker 메모리 제한은 1GB, InnoDB buffer pool은 512MB로 설정했다. 이 프로젝트에서 MySQL은 CDC 소스 역할만 하고 복잡한 쿼리를 처리하지 않으므로, buffer pool에 과도한 메모리를 할당할 필요가 없다.

---

## 3. Kafka 클러스터

### 3.1 왜 3 Broker인가

Kafka에서 `replication.factor=3`을 사용하려면 최소 3대의 Broker가 필요하다. 이렇게 하면:

- 1대가 죽어도 나머지 2대에 데이터가 있으므로 **데이터 유실 없음**
- `min.insync.replicas=2` 설정과 함께 사용하면, Producer가 최소 2대에 기록 확인을 받아야 성공으로 처리 → **강한 내구성(durability) 보장**

1 Broker로도 동작은 하지만, 장애 복구 시나리오를 테스트할 수 없고 프로덕션과 동떨어진 환경이 된다.

### 3.2 Confluent Platform 이미지를 선택한 이유

Apache Kafka 공식 이미지 대신 `confluentinc/cp-kafka:7.5.3`을 사용했다.

- 환경 변수(`KAFKA_*`)로 설정이 가능해 Docker 환경에 최적화
- Kafka Connect, Schema Registry 등 추후 확장이 용이
- 커뮤니티에서 가장 널리 사용되어 트러블슈팅 자료가 풍부

### 3.3 리스너 구성 — INTERNAL / EXTERNAL 분리

```yaml
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka-1:29092,EXTERNAL://localhost:9092
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

이것은 Kafka 초보자가 가장 많이 막히는 부분 중 하나다.

**왜 리스너를 2개로 나누는가?**

Kafka 클라이언트는 처음 접속할 때 Broker로부터 "앞으로 이 주소로 접속하라"는 advertised listener 정보를 받는다. 문제는 Docker 컨테이너 안에서의 주소(kafka-1:29092)와 호스트에서의 주소(localhost:9092)가 다르다는 것이다.

| 리스너 | 주소 | 누가 사용하는가 |
|--------|------|----------------|
| INTERNAL | `kafka-1:29092` | 같은 Docker 네트워크 안의 컨테이너들 (Debezium, Flink, 다른 Broker) |
| EXTERNAL | `localhost:9092` | 호스트에서 접근 (개발/테스트, Kafka CLI) |

만약 리스너를 하나만 쓰면, 컨테이너 간 통신이 안 되거나 호스트에서 접근이 안 되는 문제가 발생한다.

### 3.4 핵심 설정값과 근거

| 설정 | 값 | 근거 |
|------|-----|------|
| `KAFKA_HEAP_OPTS` | 512MB | 16GB 서버에서 Broker 3대 = 1.5GB. LinkedIn 벤치마크에 따르면 소규모 클러스터에서 512MB~1GB면 충분 |
| `replication.factor` | 3 | 모든 파티션이 3개 Broker에 복제. 1대 장애 시에도 데이터 안전 |
| `min.insync.replicas` | 2 | Producer가 acks=all로 보낼 때, 최소 2대가 기록해야 성공. 1대 장애까지 허용 |
| `num.partitions` | 3 | 기본 파티션 수. Consumer 병렬 처리와 Broker 수에 맞춤 |
| `log.retention.hours` | 72 | 3일간 보관. 장기 운영 시 디스크 관리 |
| `log.retention.bytes` | 1GB/partition | 파티션당 최대 1GB. 시간 + 용량 이중 제한으로 디스크 보호 |

Docker 메모리 제한을 768MB로 잡은 이유: JVM Heap 512MB + 네이티브 메모리/OS 오버헤드 약 256MB.

### 3.5 Zookeeper

```yaml
ZOOKEEPER_CLIENT_PORT: 2181
KAFKA_HEAP_OPTS: "-Xmx256m -Xms256m"
```

Zookeeper는 Kafka 클러스터의 메타데이터(Broker 목록, Topic 설정, Controller 선출)를 관리한다. Kafka 3.x부터 KRaft 모드로 Zookeeper 없이 운영 가능하지만, Confluent Platform 7.5에서는 Zookeeper 모드가 더 안정적이고 Debezium과의 호환성도 검증되어 있어 선택했다.

256MB Heap이면 이 규모의 클러스터에서 충분하다.

### 3.6 Kafka UI

운영 모니터링과 디버깅을 위해 `provectuslabs/kafka-ui`를 추가했다.

- 웹 브라우저에서 Broker 상태, Topic 목록, 메시지 내용을 실시간 확인 가능
- 이후 Cloudflare Tunnel로 외부 접근 시, 면접관에게 보여줄 수 있는 관리 화면
- 메모리 384MB 제한으로 가볍게 운영
- 포트: 8088 (기존 n8n이 8080을 사용 중이어서 변경)

---

## 4. 메모리 예산

전체 16GB 중 Phase 1 인프라가 사용하는 메모리:

```
컴포넌트         Docker 제한    실제 측정값
─────────────────────────────────────────────
MySQL            1GB            395MB
Zookeeper        384MB          75MB
Kafka Broker ×3  768MB × 3      305~312MB × 3
Kafka UI         384MB          184MB
─────────────────────────────────────────────
합계             ~4.7GB         ~1.58GB
```

시스템 전체 16GB 중 약 1.58GB를 사용한다. Phase 2 이후 추가될 Debezium(512MB), Flink(1.5GB), ClickHouse(2GB), Grafana(256MB) 등을 고려하면 총 ~6~7GB 예상. 약 9~10GB의 여유가 있어 OS 캐시와 예비 공간으로 충분하다.

---

## 5. 검증 결과

### 5.1 MySQL binlog 검증 (Day 1)

```
log_bin          = ON
binlog_format    = ROW
binlog_row_image = FULL
gtid_mode        = ON
```

debezium 유저 권한 확인 완료. orders 테이블 테스트 데이터 3건 정상 조회.

### 5.2 Kafka 클러스터 검증 (Day 2)

테스트 토픽 `test-phase1`을 생성하여 검증:

```
Topic: test-phase1  Partitions: 3  Replication Factor: 3
  Partition 0  Leader: 2  Replicas: 2,3,1  Isr: 2,3,1
  Partition 1  Leader: 3  Replicas: 3,1,2  Isr: 3,1,2
  Partition 2  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
```

- 3개 Broker 모두 ISR(In-Sync Replica)에 포함 ✅
- Leader가 Broker 1, 2, 3에 골고루 분산 ✅
- `min.insync.replicas=2` 설정 적용 확인 ✅
- Producer → Consumer 메시지 전달 정상 확인 ✅

### 5.3 재시작 복원 테스트 (Day 3)

`docker compose down` → `docker compose up -d` 후:

- 6개 컨테이너 전부 정상 기동 ✅
- MySQL 볼륨 데이터 보존 확인 (orders 테이블 3건 유지) ✅

### 5.4 Broker 장애 복구 테스트 (Day 3)

**시나리오:** Broker 3을 강제 종료(`docker stop`)한 후 클러스터 동작을 확인하고, 이후 복구하여 ISR이 원래대로 돌아오는지 검증한다.

**① 정상 상태 (장애 전)**
```
Partition 0  Leader: 2  Isr: 2,3,1
Partition 1  Leader: 3  Isr: 3,1,2
Partition 2  Leader: 1  Isr: 1,2,3
```
3개 Broker 모두 ISR에 포함. 모든 파티션이 완전 복제 상태.

**② Broker 3 강제 종료 후**
```
Partition 0  Leader: 2  Isr: 2,1
Partition 1  Leader: 1  Isr: 1,2     ← Leader가 3에서 1로 자동 전환
Partition 2  Leader: 1  Isr: 1,2
```

확인된 사항:
- ISR에서 Broker 3이 제거됨 → 정상 (죽은 Broker는 동기화 불가)
- Partition 1의 Leader가 Broker 3 → Broker 1로 **자동 재선출** → 정상
- 나머지 2대(Broker 1, 2)로 클러스터가 계속 동작

**③ 장애 중 메시지 전달**

Broker 다운 직후 Producer가 메시지를 전송하면, 리더 재선출 과도기(수 초)에 Producer가 새 리더를 찾지 못해 메시지 커밋이 실패할 수 있다. 이번 테스트에서 장애 직후 전송한 메시지는 커밋되지 않았다.

이는 `acks=all` + `min.insync.replicas=2` 설정에서 예상 가능한 동작이다. 프로덕션 환경에서는 Producer 설정에 `retries`와 `retry.backoff.ms`를 추가하여 과도기를 넘기도록 한다.

장애 상태가 안정된 후(리더 재선출 완료) 전송한 메시지는 정상적으로 전달되었다:
```
test-1
test-2
test-3
Processed a total of 3 messages
```

**④ Broker 3 복구 후**
```
Partition 0  Leader: 2  Isr: 2,1,3
Partition 1  Leader: 1  Isr: 1,2,3
Partition 2  Leader: 1  Isr: 1,2,3
```
Broker 3이 ISR에 다시 합류. 약 60초 이내에 복구 완료.

**⑤ 장애 테스트 결론**

| 항목 | 결과 |
|------|------|
| Broker 1대 다운 시 클러스터 가용성 | 유지됨 ✅ |
| Leader 자동 재선출 | 정상 동작 ✅ |
| 장애 직후 과도기 메시지 전달 | 리더 재선출 중 일시적 실패 (예상된 동작) |
| 장애 안정화 후 메시지 전달 | 정상 ✅ |
| Broker 복구 후 ISR 재합류 | ~60초 이내 복구 ✅ |
| 데이터 유실 | 없음 ✅ |

---

## 6. 트러블슈팅

### 6.1 Kafka Cluster ID 불일치

**증상:**
```
kafka.common.InconsistentClusterIdException: 
The Cluster ID Gg0YLaCaRNmYWWnhG1sHQA doesn't match 
stored clusterId Some(lKc4F836TxufcYSvvAOeVw) in meta.properties.
```

Kafka Broker가 재시작 루프에 빠지며 `Restarting` 상태가 반복됨.

**원인:**

`docker compose down`은 컨테이너를 삭제하지만 Docker Volume은 삭제하지 않는다. Zookeeper는 별도 볼륨을 지정하지 않았으므로 컨테이너와 함께 데이터가 사라져 새로운 Cluster ID를 생성했다. 반면 Kafka Broker 볼륨(`kafka1_data` 등)은 보존되어 이전 Cluster ID를 가지고 있었다. 새 Zookeeper의 Cluster ID와 기존 Kafka 볼륨의 Cluster ID가 달라서 Broker가 "잘못된 클러스터에 참여하려 한다"고 판단하고 종료한 것이다.

**해결:**
```bash
docker compose down
docker volume rm cdc-realtime-pipeline_kafka1_data \
                 cdc-realtime-pipeline_kafka2_data \
                 cdc-realtime-pipeline_kafka3_data
docker compose up -d
```

**교훈:**

Zookeeper와 Kafka 볼륨의 생명주기를 일치시켜야 한다. 향후 Zookeeper에도 명시적 볼륨을 지정하거나, 정리 시 전체 볼륨을 함께 삭제하는 스크립트를 사용해야 한다.

### 6.2 Kafka UI 포트 충돌

**증상:** `failed to bind host port 0.0.0.0:8080/tcp: address already in use`

**원인:** 기존에 운영 중인 n8n이 8080 포트를 사용 중이었다.

**해결:** Kafka UI 포트를 `8088:8080`으로 변경.

### 6.3 kafka-console-consumer의 ERROR 로그

**증상:**
```
ERROR Error processing message, terminating consumer process
org.apache.kafka.common.errors.TimeoutException
Processed a total of 3 messages
```

**원인:** `--timeout-ms 5000` 옵션으로 Consumer를 실행하면, 5초간 새 메시지가 없을 때 `TimeoutException`으로 종료된다. Kafka CLI 도구가 이 정상적인 종료를 `ERROR` 레벨로 출력하는 것은 CLI의 로깅 수준 문제이며, 클러스터 에러가 아니다.

**판단 기준:** 마지막 줄의 `Processed a total of N messages`에서 N이 예상 값과 일치하면 정상 동작이다.

---

## 7. Phase 1 → Phase 2 연결점

Phase 1이 완료되면 Phase 2에서 바로 연결할 수 있도록 다음이 준비된 상태다:

| 준비 항목 | 상태 | Phase 2에서의 용도 |
|-----------|------|-------------------|
| MySQL binlog 활성화 | ✅ | Debezium Connector가 binlog 이벤트를 읽음 |
| debezium 전용 유저 | ✅ | Debezium이 MySQL에 접속할 때 사용 |
| Kafka 3-Broker 클러스터 | ✅ | Debezium이 CDC 이벤트를 발행할 토픽 자동 생성 |
| Docker Network (cdc-network) | ✅ | Debezium 컨테이너를 같은 네트워크에 추가 |
| orders/order_executions 테이블 | ✅ | INSERT/UPDATE/DELETE로 CDC 이벤트 생성 테스트 |

Phase 2 첫 단계: `docker-compose.yml`에 Kafka Connect(Debezium) 컨테이너 추가 → Connector 등록 → orders 테이블에 INSERT → Kafka Topic에서 CDC 이벤트 확인
