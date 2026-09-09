# 09. 호가 확장 1차 실행 보고 (2026-09-09)

- 실행 창: 2026-09-08 22:58 ~ 09-09 00:55 UTC (KST 09-09 07:58 ~ 09:55)
- 지시서: 「CDC 호가 확장 — 1차 실행 지시서」. 사전 검증 보고서(`~/cdc-orderbook-probe/REPORT.md`, 09-09 아침)의 항목 번호를 그대로 참조.
- 원본 보관: `~/cdc-orderbook-probe/backup/` (producer.py, compose 5종, MySQL·ClickHouse DDL, docker inspect/images, crontab). 작업 로그: `~/cdc-orderbook-probe/worklog-phase1.md`. 관련 분석: `docs/08-ingest-lag-incident.md`.
- 표기: 시각은 UTC(호스트 시계) 기준, KST = +9h. 3열 = 실측값 / 수행 명령·변경 파일 / 특이사항.

## 작업 1. 야간 트래픽 샘플 (예약)

| 실측값 | 수행 명령·변경 파일 | 특이사항 |
|---|---|---|
| 예약 완료. 결과 미도착(13:00 UTC = 22:00 KST 실행) | 사용자 crontab `0 13 9 9 * ~/cdc-orderbook-probe/night_sample.sh` (1회성, 실행 후 자기 제거). trade / orderbook.15 각 10분, 결과 `out/trade_all_night.json`, `out/ob15_all_night.json`, 로그 `logs/night_sample.log` | 12분 상한 후 잔여 컨테이너 강제 정리 로직 포함. 대신 **국내 오전 피크(09:11~09:21 KST) 샘플을 확보**: orderbook.15 전 마켓 **261.7 msg/s(p95 375/s), payload 272.8 KB/s, 와이어 126 KB/s**, trade 53.6 msg/s — 아침 07시대 샘플(153.6 / 10.9 msg/s)의 1.7배 / 3.1배. 용량표 갱신은 야간 값까지 받은 뒤 |

## 작업 2. 체결 best bid/ask 4필드 추가

| 단계 | 실측값 | 수행 명령·변경 파일 | 특이사항 |
|---|---|---|---|
| 필드 확인 | SIMPLE 키 `bap/bas/bbp/bbs` 실수신(23:17) | `simple_keys.py` | DEFAULT 470B → SIMPLE 304B, 신규 필드 포함 |
| producer | INSERT 11컬럼, `parse_trade()`에 NULL-safe `_opt()` 4개 추가 (+14/−2줄) | `producer/producer.py` (원본 `backup/producer.py.orig`) | 배치 크기·주기 등 다른 로직 무변경 |
| MySQL DDL | 23:18:15 실행, 1초, 2,794,237행 | `ALTER TABLE crypto_db.crypto_trades ADD COLUMN best_ask_price/best_ask_size/best_bid_price/best_bid_size DECIMAL(20,8) NULL, ALGORITHM=INSTANT` | MySQL 8.0.45 INSTANT → 잠금·복사 없음 |
| Debezium | connector/task RUNNING 유지. `cdc` 토픽 p0 offset 20에 ALTER DDL 이벤트, `_schema-history`에 `best_ask_price` 2건, connect 로그 "Already applied 20 database changes" | `curl :8083/connectors/mysql-cdc-connector/status`, `kafka-console-consumer --topic cdc/_schema-history` | 온라인 DDL 추적 정상. 이후 CDC 이벤트 value 637B → 770B(+133B) |
| ClickHouse | 23:19:28 4컬럼 `Nullable(Float64)` 추가(sequential_id 뒤) | `ALTER TABLE cdc_pipeline.crypto_trades ADD COLUMN ...` | dbt/airflow의 `SELECT *`는 dbt tests 3개(ref 대상)뿐 → 무영향 |
| Flink | **미통과**. `CdcEventParser`가 필드 화이트리스트, `ClickHouseSinks` INSERT 컬럼 고정 | `flink/src/main/java/com/cdc/pipeline/function/CdcEventParser.java`, `sink/ClickHouseSinks.java:28` | 잡 재제출 필요 → 금지 범위. **MySQL·Kafka까지만 적재, ClickHouse 컬럼은 전부 NULL**. 2차 결정 |
| 재기동 | build 23:19:28~23:19:45(17초), `up -d --no-deps` 23:19:45, 기동·WS 연결 23:19:47. 마지막 수신 23:19:21 → 신규 수신 23:19:47 = **공백 26초**(컨테이너 교체 자체는 2초) | `docker compose build upbit-producer`, `docker compose up -d --no-deps upbit-producer` | cdc-* 다른 컨테이너 미접촉 확인 |
| 30분 검증 (23:50) | 5,889행 중 NULL **0(0.000%)**. 평균 스프레드 BTC 1.48bp·ETH 4.19·XRP 5.54·SOL 7.21·DOGE 83.0bp, crossed(ask≤bid) 0건, 체결가가 BBO 밖인 행 BTC 8/2,872. producer WARNING/ERROR 0, Flink 체크포인트 실패 0, health_check 4회 success | MySQL 집계 SQL(worklog 참조) | BBO 밖 8건은 체결·호가 스냅샷 시점 차로 추정(미검증). producer STATS duplicates 470/6,269(7.5%)는 INSERT IGNORE(sequential_id UNIQUE) 기존 동작 |

## 작업 3. 8/29 건수 반토막 원인 판별 → 유실 아님, 적재 지연

상세는 `docs/08-ingest-lag-incident.md`. 요약:

| 실측값 | 확인 방법 | 특이사항 |
|---|---|---|
| **판정: 시장 요인도 유실도 아닌 producer 적재 지연.** 체결시각(`upbit_timestamp`) 기준 일별 거래량은 08-18~09-06 전 기간 5코인 모두 업비트 일봉 대비 **97.5~99.9%** 일치. 적재시각(`source_ts`) 기준으로만 41~322% 요동 | REST `/v1/candles/days` 5코인×20일 vs ClickHouse 일별 `sum(trade_volume)` 두 기준으로 대조 | 사전 검증의 "8/29 반토막"은 백로그 배수 완료 후 정상 복귀 |
| 지연 p50 08-19 18:00 UTC 3,565s → **08-22 15:00 UTC 132,933s(36.9h)** → 08-30 00:00 정상. 큐 깊이 최대 **1,082,811행**(08-22 15:00). 08-31 재발(54K), 9월에도 피크 시간대 p50 60~1,475s | 시간별 `source_ts − upbit_timestamp`, 큐 깊이 = `upbit_ts ≤ T AND source_ts > T` | |
| 원인: `producer.py` flush가 2초당 1회 × 20행 = 이론 10 rows/s 상한. 배수 구간 시간당 처리량 p50 **7.95 rows/s, 변동계수 0.047**(포화), 정상 구간 CV 0.428 | 코드 `BATCH_SIZE=20`, `BATCH_INTERVAL_SEC=2.0`, `flush()` 1회 호출 | 트리거: 08-22 05:00 UTC 시간당 261,895건(72.75 msg/s) |
| 미감지 사유: health_check·Grafana·Flink 지표 모두 `source_ts` 이후 구간만 측정. `upbit_timestamp` 기반 지연 지표 0건 | `grep upbit_timestamp` 대시보드/DAG/마트 | producer 재기동으로 이전 컨테이너 로그 소실 → 백로그를 ClickHouse로 재구성 |

## 작업 4. 디스크·메모리 위생

| 항목 | 실측값 | 수행 명령·변경 파일 | 특이사항 |
|---|---|---|---|
| 빌드캐시 | 7.016GB 회수 (df 95G → 90G) | `docker builder prune -f` (23:18:46, 진행 중 빌드 없음 확인) | |
| 미사용 이미지 | 7개 삭제 ≈4GB (df 90G → 86G): umami 1.11GB, circuit-flink-temp/build-tmp 1.2GB×2, circuit-flink-builder, adminer, mariadb:11, alpine | `docker rmi <7 ids>`; 목록·보존 사유 `out/images_delete_list.txt` | 보존 6개: icepush-api:backup-preharden(명시적 백업), couple-board-app·hotel-backend(본인 앱), icepush-dbt, metabase·airflow 베이스(레이어 공유, 회수 0). `docker system df`의 "30.8GB reclaimable"은 중지 컨테이너 이미지·공유 레이어를 포함한 값이라 달성 불가 |
| ClickHouse system.* TTL | **15.91GiB → 2.41GiB** (/var/lib/clickhouse 20G → 8.8G, df 86G → 76G). 5테이블 `TTL event_date + 14 DAY` 메타데이터 적용, 202602~202608 파티션 DROP | `ALTER TABLE system.{part_log,metric_log,query_log,asynchronous_metric_log,trace_log} MODIFY TTL ... / DROP PARTITION` | **사고**: MODIFY TTL이 자동 실행한 MATERIALIZE TTL 뮤테이션이 메모리 한도(1.13GiB) 초과로 실패·재시도(metric_log 2.42GiB 요구) → ClickHouse CPU 345%, 메모리 1.16/1.25GiB. 23:31:25 `KILL MUTATION` 3건으로 해소(CPU 9.6%). 이후 DROP PARTITION(메타데이터 연산)만 사용. `query_views_log` 1.87GiB는 지시 목록 밖이라 미처리. `clickhouse/config.d/system-logs-ttl.xml` 준비만(마운트·재시작 필요 → 보류) |
| mem_limit | 8개 적용, 30분 관찰 재시작 0·OOM 0. **n8n-n8n-1은 원복(제한 없음)** | compose 4개 파일 `deploy.resources.limits.memory` + `docker update` my-postgres. 표는 아래 | **사고**: 00:01~00:25 UTC n8n-n8n-1 크래시 루프 57회(exit 134, V8 heap out of memory). 상세 아래 |
| fds-generator | 2026-02-12 00:42 UTC 이후 exited(rc 137), compose `profiles: ["pipeline"]` 게이트 | `docker inspect`, compose | 상시 부하 없음 |
| 검증 | df **95G → 81G**(00:55 시점, 정리 직후 76G에서 +5G는 원인 미추적). swap 4.3GB(시작) → 6.9GB(00:20) → 6.1GB(00:55). cdc-* 무영향 | `df -h`, `free -m` | swap이 줄지 않은 것은 예상 범위(회수한 것은 디스크) |

mem_limit 적용표:

| 컨테이너 | 적용 전 RSS(3회 평균, 스왑 상태) | 설정값 | 재기동 후 실사용(00:55) | 상태 |
|---|---|---|---|---|
| n8n-n8n-1 | 270MiB | 416M → 576M → **제한 제거** | 335MiB | 정상, 재시작 0(00:25 이후) |
| n8n-worker-1 | 283MiB | 432M | 290MiB | 정상 |
| n8n-postgres-1 | 93MiB | 256M (규칙값 144M 대신, shared_buffers 128MB) | 37MiB | 정상 |
| n8n-redis-1 | 3MiB | 128M | 5MiB | 정상 |
| icepush-api | 17MiB | 128M | 54MiB | 정상 |
| circuit-connect-api | 23MiB | 128M → **256M** | 119MiB | 정상(128M 유지 시 93%) |
| fds-redis | 4MiB | 128M | 11MiB | 정상 |
| my-postgres | 11MiB | 256M (`docker update`, compose 없음) | 10MiB | 정상, 무재기동 |
| fds-generator/consumer, airflow-init | exited | 512M (compose만) | – | 미기동 |

n8n 사고 사실관계: 23:29 UTC에 편집한 n8n compose를 사용자 crontab의 n8n 자동 업데이트(`0 0 * * * /home/calme/n8n/update-n8n.sh`)가 00:00:59 UTC에 먼저 적용해 4개 컨테이너를 재생성(이미지 digest 307d6065 동일, 버전 변화 없음). 이후 n8n-n8n-1이 23초 간격으로 exit 134 `FATAL ERROR: Ineffective mark-compacts near heap limit Allocation failed - JavaScript heap out of memory`. V8가 cgroup 제한을 힙 상한으로 환산(576M에서 `heap_size_limit` 실측 312MB, 제한 없음 4,192MB)하는데 n8n 기동 힙 수요가 그 이상. `docker update --memory 0`은 미반영 → `docker compose up -d n8n`으로 00:25:26 재생성해 복구. 이 n8n은 CDC 이상거래 Slack/Gmail 알림 워크플로우를 실행하므로 **약 24분간 알림 중단**. cdc-* 파이프라인 무영향.

## 작업 5. Flink 수용량 산정 + 상태 조사 (변경 없음)

| 항목 | 실측값 | 확인 방법 | 특이사항 |
|---|---|---|---|
| 현행 메모리 배분 | process 1,024M = overhead 192 + metaspace 256 + framework heap 128 + framework off-heap 128 + network 64 + managed 230.4 + **task heap 25.6M**. 실측 Heap.Max 156MB(used 103), **Metaspace 230/256MB(90%)**, managed 241MB 전량 RocksDB | REST `/taskmanagers/<id>`, `/metrics` | 잡 1개 추가 시 Metaspace 여유 26MB → OOM: Metaspace 위험 |
| (a) 호가 잡 +1슬롯(4슬롯) | `process.size 2g, metaspace 384m, managed fraction 0.25` → task heap 692M(슬롯당 173M), network 146M | Flink 1.18 메모리 모델 계산(`out/flink_memory_model.txt`) | 1.5g는 task heap 224~368M로 부족. compose 제한은 2,304M 권장(+1GB) |
| (b) 재생 실험 잡 추가(5슬롯) | `process.size 2.5~3g, metaspace 512m, managed 0.2~0.25` → task heap 909~1,321M(슬롯당 182~264M) | 동일 | 호스트 available 9.0GB(00:55)·swap 6.1GB 사용 중이라 +2GB는 스왑 압박. 분리 TM 권장(아래) |
| 상태 596MB 정체 | **누수·누적 아님.** TM 로컬 RocksDB 실측: AnomalyDetector subtask1 `MANIFEST-000004` **352.7MB**, SST 15개 합 ~15KB, WAL 0B; subtask2 MANIFEST 176.9MB; Window 연산자 55.7/39.6MB. 키드 상태 자체(5마켓×5 ValueState)는 수십 KB | `docker exec cdc-flink-taskmanager ls -la /tmp/tm_*/tmp/job_dd0c*/db` | 네이티브 풀 체크포인트가 MANIFEST(플러시/컴팩션 버전 기록, 체크포인트당 ≈3.3KB × 105,913회)를 매번 통째로 복사. 25분 샘플러에서 625.0→625.1MB 단조 미세 증가. 상한은 RocksDB max_manifest_file_size(기본 1GB) 롤오버 |
| 코드 확인 | 5분 tumbling processing-time window(`CdcPipelineJob.java:70-72`), AnomalyDetector는 ValueState 5개(`AnomalyDetector.java:78-96`), state TTL·타이머 없음 | 소스 | 상태가 작으므로 `state.backend: hashmap` 전환이 합리적(체크포인트 수 KB, managed 230MB → task heap 전환). 2차 결정 |
| TM 분리 vs 슬롯 증설 | 분리 TM: 고정비 overhead 192M + metaspace 256M ≈ 450MB 추가, 실험 잡 장애가 프로덕션 TM에 전파되지 않음. 슬롯 증설: 고정비 없음, TM 크래시 시 프로덕션 2잡 동반 재시작, Metaspace 공유 | 계산 | **권고: 프로덕션 호가 잡은 기존 TM 슬롯 증설(a), 재생 실험 잡은 별도 TM 컨테이너** |

## 추가 실험 (사용자 승인). Oracle vs 미니PC 수집 지연

동시 측정 2회(08:44~08:54, 09:11~09:21 KST), 각 10분, 단일 커넥션, KRW 287마켓. 스크립트·라이브러리·구독 동일, 회선·CPU 아키텍처·런타임 이미지 상이, 파이프라인 스택 비관여. 미니PC NTP 오프셋 −3.8ms, Oracle −0.05ms.

| 지표 (2회차, 오전 피크) | 미니PC | Oracle |
|---|---|---|
| trade msg/s / 건수 | 53.57 / 32,143 | 53.58 / 32,153 |
| trade 지연 p50 / p95 / p99 | 29.7 / 36.4 / 126.2 ms | 28.9 / 34.9 / 125.4 ms |
| orderbook msg/s / 건수 | 261.67 / 157,006 | 261.51 / 156,910 |
| orderbook 지연 p50 / p95 / p99 | 27.9 / 35.7 / 58.9 ms | 27.2 / 32.6 / 59.0 ms |
| 100ms 이상 꼬리(건) | trade 358 / ob 675 | trade 349 / ob 636 |
| TCP connect / TLS(curl) | 11~16 / 34~52 ms | 5.6~7.1 / 60~80 ms |

결론: 건수·드랍·p95·꼬리 모두 동일. 집 회선은 262 msg/s·126KB/s 규모에서 병목이 아님. trade p99는 SNAPSHOT 287건(0.9%)이 오염. 전체 파이프라인 기준으로는 미니PC 외 대안 없음(Oracle available 6.4GB·디스크 29GB·A1 한도 99.2%).

## 변경 파일 목록

| 파일 | 변경 | 비고 |
|---|---|---|
| `producer/producer.py` | best 4필드 파싱·INSERT | 이미지 재빌드·재기동 완료 |
| `docker-compose.yml` | airflow-init `deploy.resources.limits.memory: 512M` | 미기동 서비스, 재시작 없음 |
| `clickhouse/config.d/system-logs-ttl.xml` | 신규(미마운트) | 적용 보류 |
| `docs/08-ingest-lag-incident.md`, `docs/09-orderbook-phase1-execution.md` | 신규 | |
| `/home/calme/n8n/docker-compose.yml` | postgres 256M, redis 128M, worker 432M (n8n은 제거) | |
| `/home/calme/icepush/server/compose.yml` | icepush-api 128M | |
| `/home/calme/circuit-connect/circuit-connect-backend/docker-compose.yml` | api 256M | |
| `/home/calme/fds-pipeline-lab/docker-compose.yml` | redis 128M, generator/consumer 512M | |
| MySQL `crypto_db.crypto_trades`, ClickHouse `cdc_pipeline.crypto_trades` | 컬럼 4개 추가 | DDL 원본 backup/ |
| ClickHouse `system.*` 5테이블 | TTL 14d, 파티션 DROP | |
| 사용자 crontab | 야간 샘플 1회성 항목 | 실행 후 자기 제거 |

미커밋. `dbt_cdc_pipeline/tests/assert_no_long_gaps.sql`의 기존 미커밋 diff는 건드리지 않음.

## 2차 지시가 필요한 결정 항목

1. **Flink 상향폭**: (a) `taskmanager.memory.process.size 2g` + `jvm-metaspace 384m` + `managed.fraction 0.25` + 슬롯 4 (compose 제한 2,304M). 상태 백엔드 `hashmap` 전환 여부(체크포인트 596MB → 수 KB, managed 메모리 회수). 재생 실험 잡은 별도 TM 컨테이너.
2. **best bid/ask의 Flink 통과**: `CryptoTradeEvent`·`CdcEventParser`·`ClickHouseSinks` 4필드 추가 + 잡 재제출(savepoint). 1번과 같은 재제출 창에서 묶어 처리 권장.
3. **producer 처리량 상한 제거**(docs/08 후속 A): flush 루프를 버퍼 소진까지 반복, 버퍼 상한·경고. 전 코인 확장(체결 53.6 msg/s 피크 실측) 전 필수. + 지연 지표(`source_ts − upbit_timestamp`) Grafana·health_check 추가(B).
4. **호가 착수 조건**: 디스크(371G 여유)는 충족. Flink 산정은 완료했으나 적용 전. → 1번 적용 후 착수.
5. **system.* TTL 영속화**: config.d 마운트 + ClickHouse 재시작 필요(재시작 시 테이블 정의 불일치로 rename될 수 있어 config와 ALTER 값 일치 유지). `query_views_log` 1.87GiB 처리 여부.
6. **n8n 메모리 제한**: `NODE_OPTIONS=--max-old-space-size`와 함께 재설정할지, 제한 없이 둘지. 미니PC n8n과 Oracle n8n이 동시에 CDC 알림 워크플로우를 돌리는지 확인 필요(이번 세션 미확인).
7. **야간 샘플 반영**: 13:00 UTC 결과로 용량표 갱신(오전 피크 실측 261.7 msg/s 기준 .15 Kafka 일일 raw ≈ 23GB로 상향될 가능성).
