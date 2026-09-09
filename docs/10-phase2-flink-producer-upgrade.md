# 10. 2차 실행 — Flink 재구성 + best bid/ask 통과 + producer 처리량 상한 제거 (2026-09-09)

- 실행 창: 2026-09-09 01:36 ~ 02:16 UTC (KST 10:36 ~ 11:16). 사용자 승인 항목: 1차 보고(`docs/09`)의 2차 결정 1·2·3번을 한 번의 Flink 재제출 창에 묶어 처리.
- 원본 보관: `~/cdc-orderbook-probe/backup/phase2/` (flink/src 전체, 02-23 빌드 JAR, 1차 producer.py, 1차 compose, 정지 전 오프셋·ClickHouse 기준값, stop/restore 출력). 작업 로그: `~/cdc-orderbook-probe/worklog-phase1.md` 하단.
- 표기: UTC. 3열 = 실측값 / 수행 명령·변경 파일 / 특이사항.

## 1. 변경 내용

| 대상 | 변경 | 파일 | 근거 |
|---|---|---|---|
| Flink TaskManager | `process.size 1g → 2g`, `jvm-metaspace 256m → 384m`, `managed.fraction 0.4 → 0.25`, 슬롯 3 → 4, 컨테이너 제한 1280M → 2304M | `docker-compose.yml` flink-taskmanager | 1차 실측 task heap 25.6MiB, Metaspace 90% (docs/09 작업 5) |
| Flink 상태 백엔드 | `state.backend: rocksdb → hashmap` (JM/TM), `execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION` 추가 | 동일 | 키드 상태가 수십 KB인데 RocksDB MANIFEST 때문에 체크포인트 625MB (docs/09 작업 5) |
| CDC 잡 | `CryptoTradeEvent`에 `Double bestAskPrice/bestAskSize/bestBidPrice/bestBidSize`, `CdcEventParser.parseNullableDecimal()`, `ClickHouseSinks` INSERT 17컬럼 + `setNullableDouble()`, Kafka 시작 오프셋 `latest() → committedOffsets(LATEST)` | `flink/src/main/java/com/cdc/pipeline/{model/CryptoTradeEvent,function/CdcEventParser,sink/ClickHouseSinks,CdcPipelineJob}.java` | 1차에서 MySQL·Kafka까지만 도달하던 4필드를 ClickHouse까지 통과. 오프셋 변경은 savepoint 없는 재시작 시 `latest`로 점프해 유실되던 경로를 커밋 오프셋 재개로 교체 |
| producer | `flush()`가 버퍼 소진까지 반복(1회당 최대 `MAX_BATCHES_PER_FLUSH=50` 배치), STATS에 `buffer=` 추가, 버퍼 5,000행 초과 시 1분당 1회 WARNING. compose `BATCH_SIZE 20 → 100` | `producer/producer.py`, `docker-compose.yml` | 2초당 1배치(20행) = 10 rows/s 상한이 8월 36.9h 지연의 원인 (docs/08). 새 이론 상한 100×50/2s = 2,500 rows/s |

## 2. 실행 절차와 실측

| 단계 | 실측값 | 수행 명령 | 특이사항 |
|---|---|---|---|
| 사전 점검 | df 366G 여유, available 8.0GB, swap 5.3GB 사용(상위: mysqld 710MB, airflow gunicorn 5개 합 1.9GB) | `free`, `/proc/*/status VmSwap` | 필수 정리 없음. 선택 후보: airflow/logs 3.1G, journal 1.6G, query_views_log 1.88GiB |
| 빌드 | Flink JAR 42MB(01:41:31~01:42:27, 도커 멀티스테이지), producer 이미지 75.7MB | `scripts/build-flink-job.sh`, `docker compose build upbit-producer` | JAR 내 `CryptoTradeEvent.class`에 bestAskPrice 확인 |
| producer 단위테스트 | 4케이스 통과: 5,000행 1회 소진(50배치, 1.2ms), 12,000행은 5,000 상한 후 잔여 유지+경고, 3번째 배치 실패 시 해당 배치 버퍼 앞 복원·순서 보존 | `~/cdc-orderbook-probe/test_flush.py` (가짜 커서, 프로덕션 미접근) | |
| 정지 전 기준 | 커밋 오프셋 p0 89,215,036 / p1 89,181,993 / p2 89,212,975 (lag 19~23). ClickHouse 최근 max trade_id **95,997,125**(source_ts 01:42:49.039) | `kafka-consumer-groups --describe`, ClickHouse | health_check DAG는 01:42:48 pause(오탐 방지) |
| savepoint 정지 | CDC 01:42:53 → `savepoint-dd0c41-0cb7e3ec0577` **20KB, 파일 1개**. circuit 01:42:58 → `savepoint-f27298-76d8cc7ff926` 116KB | `flink stop --savepointPath /opt/flink/savepoints <jid>` (CANONICAL) | 네이티브 체크포인트 625MB vs canonical savepoint 20KB → 상태 소량 확정 |
| Flink 재생성 | 01:43:18 `up -d` → TM 9초 만에 등록. 실효: process 2048M = overhead 205 + metaspace 384 + fw heap 128 + fw off-heap 128 + network 146 + managed 365 + **task heap 692M**. slots 4 | `docker compose up -d flink-jobmanager flink-taskmanager`, REST `/taskmanagers/<id>` | JM 768m 유지 |
| 복원 | circuit 01:43:33 (JobID 55988aed…), CDC 01:43:37 신규 JAR (JobID 9ad0f8de…) → 둘 다 RUNNING, 슬롯 3/4 | `flink run -d -s file:/opt/flink/savepoints/<sp> /opt/flink/usrlib/<jar>` | `--allowNonRestoredState` 미사용 → 연산자 uid 불일치 시 실패하도록(무음 유실 방지). 실패 없음 |
| producer 재기동 | 01:44:24 → 01:44:27 기동(3초). 이전 컨테이너 최종 STATS 01:43:54 | `docker compose up -d --no-deps upbit-producer` | 이번엔 재기동 전 `docker logs` 최종 라인을 로그에 보존 |
| 즉시 검증 (01:45:53) | 체크포인트 2/2 완료, **state 17.8KB, e2e 74~163ms**. 오프셋 p0 89,215,159(> 정지 전 89,215,036) 연속 소비. 정지 후 첫 행 trade_id **95,997,126 = 정지 전 max + 1**. 정지 구간(source_ts 01:42:40~01:43:10) 69행 = uniqExact 69 | REST, ClickHouse | **유실 0, 중복 0**. 정지 직후 6행(95,997,126~131, flink_ts 01:42:54)은 구 JAR가 barrier 이전에 처리해 best_* NULL — 정상 |
| 30분 검증 (02:16:00) | 체크포인트 32/32, state 17.8~18.0KB, e2e avg **51ms** max 163ms(이전 avg 1,388ms). Metaspace **81/384MB**(이전 230/256), Heap 138/822MB. producer WARNING/ERROR 0, `buffer=1`, received 7,168 / inserted 6,678 / duplicates 489. health_check 01:30~02:00 4회 success. ClickHouse 30분 6,565행, best_* NULL **0**, cdc_latency 5.3ms, **ingest lag(source_ts−upbit_ts) p50 1,670ms / max 4,025ms**, Kafka→Flink 1,708ms, trade_aggregations 25행(5마켓×5창) | 위와 동일 + ClickHouse 집계 | TM 962MiB/2.25GiB, JM 339MiB, ClickHouse 1.04/1.25GiB. 호스트 used 8.9GB, swap 4.1GB |

## 3. 전후 비교

| 지표 | 변경 전 (1차 실측) | 변경 후 (02:16) |
|---|---|---|
| 체크포인트 크기 | 625MB (RocksDB MANIFEST) | **17.8KB** |
| 체크포인트 e2e | avg 1,388ms, max 12,686ms | **avg 51ms, max 163ms** |
| TM task heap | 25.6MiB | 692MiB |
| TM Metaspace 사용률 | 230/256MB (90%) | 81/384MB (21%) |
| 슬롯 여유 | 0/3 | 1/4 |
| producer 이론 처리 상한 | 10 rows/s | 2,500 rows/s |
| producer 백로그 가시성 | 없음 | STATS `buffer=`, 5,000행 초과 WARNING |
| best bid/ask | MySQL·Kafka까지 | ClickHouse까지 (NULL 0) |
| 재시작 시 Kafka 시작점 | latest (유실 위험) | 커밋 오프셋 재개 |

## 4. 남은 항목 / 다음 결정

- producer 처리량 상한 제거는 **실부하 검증 전**: 피크 유입(실측 최대 72.75 msg/s)이 오기 전까지 `buffer=` 값이 0~한 자릿수인 것만 확인됨. 검증은 (a) 다음 KST 09시·22시 피크의 ingest lag p50, (b) 녹화-재생 실험에서 확인.
- ingest lag 지표(`source_ts − upbit_timestamp`)의 Grafana 패널·health_check 알림 추가는 미실시(docs/08 후속 B).
- 호가 착수 선행조건(디스크 정리 + Flink 산정·적용)은 이 시점에 충족. 슬롯 1개 여유, task heap 692M.
- 야간 샘플(13:00 UTC) 결과로 용량표 갱신 예정.
- 변경 파일은 미커밋. 1차 변경분과 함께 커밋 여부 결정 필요.
