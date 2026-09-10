# 작업 로그 (worklog) — CDC 파이프라인 호가 확장 · 유실/지연/중복 개선

> 목적: 모든 판단의 근거(실측 명령·시각·수치)를 시간순으로 남긴다. 시각은 UTC(호스트 시계) 기준, KST = +9h.
> 산출 문서: `docs/08-ingest-lag-incident.md`(적재 지연 사고), `docs/09-orderbook-phase1-execution.md`(1차 실행), `docs/10-phase2-flink-producer-upgrade.md`(2차 실행). 사전 검증 원문·프로브 스크립트·측정 JSON은 `~/cdc-orderbook-probe/`.

## 현재 상태 (최신화 2026-09-10 03:15 UTC / 12:15 KST)

- **7일 무변경 관찰 진행 중** (09-09 06:10 ~ 09-16 06:10 UTC, 태그 `obs-week1-start`). 예외 조치 1건 발생: 09-10 01:20 유니크 키 교체 + 백필(docs/13). 나머지 구조·설정 변경 없음.
- 운영: 체결 CDC 경로 287마켓(producer → MySQL → Debezium → Kafka → Flink → ClickHouse), 호가 직접 경로 287마켓, Flink 잡 3개(hashmap, TM 2g, 슬롯 4/4), ClickHouse 1.75G, health_check(잡 3·적재 지연 알림), n8n 알림은 Oracle만, 5분 지표 cron + 06:20 UTC 일일 요약.
- 데이터 상태: 체결 유실 재발 차단(01:20 이후 원장 대조 100%), 09-03 이후 7일치 복구 완료(210,469행), 09-03 이전 손실은 복구 불가(추정 5.3%). 중복 0(백필 실수분 53,012 삭제 완료). 백필 창(09-10 01:52~03:02) trade_aggregations 오염은 기록만.
- 관찰 뒤 순서(확정): (1) **reconcile_trades DAG** + dbt 유일성·커버리지·freshness 테스트 → (2) 관찰 분석(docs/14 예정)·튜닝(USDT LARGE_TRADE 임계, 파티션 키 market, Float64→Decimal, "하루" 정의 통일, 마켓 목록 갱신) → (3) 재생 증폭 실험(브로커 장애 시나리오) → (4) 브로커 3→1 + KRaft → (5) CDC 유의미화 A(가상 매매 원장)+B(이상탐지 케이스 관리), MySQL DROP PARTITION 전환, ReplacingMergeTree.
- 실험 도구: `~/pipeline-load-lab`(로컬 git) recorder/replayer/measure dry-run 검증 완료. 코퍼스는 관찰 종료 시 고정.
- 미커밋 잔여: dbt tests/assert_no_long_gaps.sql(이전 세션 diff), png 3개, 일일 요약 자동 추가분.
- 문서 인덱스: docs/07 중복 감사 · 08 적재 지연 사고 · 09 1차 · 10 2차(Flink) · 11 호가 · 12 관찰 계획 · **13 sequential_id 충돌 유실 사고** · worklog(결정 30건 + 시간순).

## 결정 사항 (확정, 재논의 불필요)

| 일시 | 결정 | 근거 |
|---|---|---|
| 09-09 00:10 UTC | 호가 수집 경로는 MySQL/Debezium 경유가 아닌 **수집기 → Kafka 직접 발행**(별도 토픽, retention 24h, RF2) | 호가는 체결의 14배 건수·48배 바이트(실측), Debezium 봉투 +34% |
| 09-09 00:10 | 호가 스펙 **count .15, 원본 TTL 7일 + 1분 파생지표 365일**(A안) | 사전 검증 §4 용량표 |
| 09-09 00:10 | 호가 착수 선행조건 = 디스크 정리 + Flink TM 산정·적용 | 1차·2차로 충족(02:16) |
| 09-09 00:10 | 부하 실험은 **녹화-재생 증폭**(실데이터 50→100→200건/s 계단), Faker 미사용 | 개선 전후 동일 입력 비교 |
| 09-09 00:10 | Spark 비교는 Oracle 임시 컨테이너(arm64, executor 1GB부터), 신규 인스턴스 금지 | A1 무료 한도 99.2% 실측 |
| 09-09 00:10 | 비CDC 컨테이너는 제거 대신 mem_limit 공존 | — |
| 09-09 00:10 | 서사 순서: 중복(완료) → 유실(완료: 지연으로 판정) → 지연(재생 실험) | JD "지연·유실·중복 직접 개선" |
| 09-09 01:20 | 2차 = Flink 상향+hashmap, best bid/ask Flink 통과, producer 상한 제거를 한 재제출 창에 묶음 | docs/09 결정 1·2·3 |
| 09-09 02:30 | **confluent-kafka** 의존성 승인(호가 수집기) | librdkafka, idempotent·zstd, 호스트에 2.13.2 존재 |
| 09-09 02:30 | **3브로커 유지**(단일 호스트, HA 아님을 문서화). 재생 실험에 브로커 장애 시나리오 포함해 비용 정당화 | 복제·ISR·페일오버 실측 가능 |
| 09-09 02:30 | **KRaft 전환은 재생 증폭 실험 뒤** 별도 유지보수 창(전후 실측 문서화) | 실험 기준선 보존, Kafka 3.6 마이그레이션 지원 |
| 09-09 04:05 | **브로커 3 → 실험 → 1**: 재생 증폭 실험까지 3브로커 유지(브로커 장애 시나리오 1회 실측·문서화) → KRaft 전환 창에서 **1브로커 combined 모드**로 축소(RF3 토픽 파티션 재배치 포함) | 단일 호스트라 HA 이득 없음. 실측 비용: 브로커 3개 RAM ~1.9GB+ZK 176MiB, 체결 RF3 쓰기 2GB/일, 호가 RF2 ~20GB/일, swap 4~6GB 사용 중 |
| 09-09 04:20 | ClickHouse 컨테이너 메모리 1.25G → 1.75G, JDBC 배치(500/2s) 유지, 호가 마트·패널은 실험 뒤 | 호가 인서트 후 ClickHouse 1.087/1.25GiB(87%) 실측. 재시작은 3잡 savepoint 정지로 유실·중복 0 |
| 09-09 04:28 | **7일 무변경 관찰** → 분석·튜닝 → 재생 증폭 실험 순. 관찰 지표는 cron 스크립트로 수집(Airflow DAG 대신) | 10분·30분 확인은 스모크 테스트일 뿐. 피크(KST 09/22~24시)·주말 사이클은 1주 필요. 재생 실험은 안정 기준선 필요 |
| 09-09 04:30 | 관찰 중 병행 작업: (1) cron 지표, (2) health_check 적재 지연 알림, (3) 재생 도구 준비(녹화기·재생기, 덤프 1회), (5) 포트폴리오 문서. 금지: RMT 전환·2월 백필, KRaft, 마트 재집계, 재생 본실행 | 프로덕션 부하를 주는 작업은 관찰 오염 |
| 09-09 04:32 | 별도 GitHub 리포: 시스템(compose·수집기·Flink·DDL·Airflow·docs)은 현 리포 유지, 실험 도구·관찰 데이터·분석·Spark 비교만 새 리포(권고, 미확정) | 배포 단위 기준 분리, 서사 단절 방지 |
| 09-09 04:33 | **전 코인(287마켓) 체결 확장을 7일 관찰 전에 실행**. 선행: MySQL EVENT 상향(승인), Kafka retention.bytes 상향(승인), producer 전 마켓 옵션, 1시간 dry-run(알림 폭주·buffer 확인) | 관찰이 "전 코인 규모에서 producer 개선이 버티는가"까지 검증. 피크 실측 53.6 msg/s vs 새 상한 2,500 rows/s |
| 09-09 04:34 | 관찰 전 정비 4건: health_check expected_jobs 2→3, Flink 재시작 전략 3×10s→20×30s(잡 코드, 다음 재제출), n8n 이중 실행 확인(→ 이중 확인됨, 처리 결정 대기), Debezium tombstones.on.delete=false(제안, 승인 대기) | ClickHouse 재시작 실측 24초 > 재시작 창 30초 아슬. delete+tombstone이 체결 토픽 트래픽 84% |
| 09-09 05:05 | Debezium 삭제 처리 순서: **지금 tombstones.on.delete=false만**(dry-run 후 적용) → 7일 관찰 중 "10분 40K 삭제 버스트" 비용 실측 → 관찰 뒤 MySQL 일 단위 파티션 + DROP PARTITION으로 청소 방식 전환(행 단위 삭제 이벤트 자체를 없앰). delete 필터(skipped.operations=d)는 보류 | 2월 46h 장애 = 50K 일괄 DELETE → delete+tombstone 10만 건 → 파서 NPE(docs/06). 체결 토픽 메시지 84%가 delete+tombstone(실측). 토픽은 compact가 아니라 tombstone 무용 |
| 09-09 05:10 | **CDC 정체성 정리**: 체결→MySQL→CDC 경로는 업무 필요가 아니라 CDC 운영 경험용(README 명시). 실무라면 체결은 호가처럼 직접 발행이 정답. 면접 프레임: "CDC 운영을 끝까지 겪고 비용(봉투 +34%, 쓰레기 84%, 46h 장애)을 실측했기에 호가는 직접 발행으로 갔다". 구조는 지금 안 바꿈 | 관찰 계획·지연 사고 서사 보존. "실무 관행과 같다"는 방향만 같고 구현은 단일 호스트 타협본임을 문서에 구분 표기 |
| 09-09 05:20 | **관찰 뒤 CDC 유의미화 방향(제안, 관찰 뒤 확정)**: A) 가상 매매 서비스 원장(orders/positions/balances, 체결 판정은 실시간 호가·체결 기준, "가상 주문" 명시) = 규모·원장 CDC·상태 upsert·스트림-상태 조인·실시간 손익. B) 이상탐지 케이스 관리 테이블(open→확인/오탐/종료를 규칙으로 갱신) → CDC → ClickHouse 정밀도 이력 → 임계값 재조정 순환. C) Airflow/n8n 메타 DB CDC는 보너스. **실주문(myOrder) 혼합은 제외** | 업비트 공개 API에 타인 주문·취소·청산 데이터 없음(문서 확인: 공개 WS ticker/trade/orderbook/candle, 인증 WS myOrder/myAsset 본인 한정, 선물·청산 없음). 실주문 반복은 허수성·취소 과다 감시 대상, 돈·키 관리 위험. 순서: 관찰 → 재생 실험 → A+B |
| 09-09 05:30 | **호가 데이터의 성격과 활용 근거**: 업비트 호가는 주문 단위(L3)가 아니라 가격대별 잔량 합계 스냅샷(L2). 취소는 잔량 감소로 반영되며 "누구의 주문"만 없음. 체결 스트림과 대조하면 "체결 없이 사라진 잔량 = 취소·정정"으로 추론 가능 → 2월(docs/06)에 "호가 없어 불가"로 남긴 감시 유형 2(허수성 매매)·3(취소·정정 과다) 탐지를 확장 항목으로 등록. 원본 7일 보관 이유 = 이 추론 계산의 입력 | 실측: 15단 전체 스냅샷 258 msg/s, 스냅샷 간 diff로 이벤트 복원 가능. L2는 스프레드·깊이·불균형 분석의 업계 표준 형태 |
| 09-09 05:30 | **실무자 관점 자체 평가(면접 대비)** — 강점: 200일+ 실운영, 사고 3건(46h 중단·37h 적재 지연·24분 알림 중단)의 원인·수치 문서화, 결정마다 실측 근거, 지연·유실·중복 각각 해결, 규모 하루 약 2,600만 이벤트(체결 400만+호가 2,200만, 초당 ~300). 약점(인정하고 답할 것): 단일 호스트 HA 아님(→3→1 결정), 체결 CDC 소스 인위적(→A+B), 싱크 at-least-once·멱등 upsert 미적용(→RMT 관찰 뒤), 스키마 계약·CI·IaC·SLO 없음, Iceberg 미구현. "왜 아직 안 했나" 답: 먼저 측정(중복 감사·7일 관찰)하고 게이트를 세운 뒤 전환하는 순서 | 약점을 먼저 말할 수 있는 것이 신뢰 요소 |
| 09-10 01:19 | **관찰 예외 조치**: MySQL 유니크 키 `(market, sequential_id)`로 교체(무중단) + REST 7일 원장 백필 210,469행(MySQL 경유 CDC) | 진행 중 유실(287마켓 2.94%, BTC 10%). 독립 수신기 실험으로 producer 원인 확정(docs/13) |
| 09-10 03:10 | **관찰 뒤 1순위 = `reconcile_trades` Airflow DAG**: 시간 파티션·catchup·멱등, 시간봉 대조 → 99% 미만 셀만 REST 원장 조회 → 누락 INSERT(MySQL 경유) → 재검증 → 품질 테이블 적재 → Slack 요약. REST pool 페이싱 ≤7/s, SLA "전날분 다음 날 정오까지". 이어서 dbt `unique_combination_of_columns(market, sequential_id)`·커버리지 테스트·source freshness(upbit_timestamp) | 원장 보존 7일 창 안에 자동 복구가 돌아야 유실이 영구화되지 않음. 오늘 수동 절차를 그대로 태스크화 |
| 09-09 04:40 | n8n 이중 실행 해소: **미니PC 워크플로우만 비활성화**(컨테이너·자동 업데이트 cron 유지) | 실행 이력 대조로 이중 실행 실증. 워크플로우 비활성은 롤백이 가장 쉬움. Oracle이 6월 컷오버본 |
| 09-09 06:09 | 재생 실험 도구는 별도 리포(`pipeline-load-lab`, 로컬), 재생기는 `load_test.` 외 토픽 거부 | 프로덕션 토픽 오발행 방지 안전장치. 관찰 중엔 읽기·dry-run만 |
| 09-09 06:20 | README에 단일 호스트 HA 아님·체결 CDC 인위성·타협점을 명시 | 면접에서 약점을 먼저 말하는 것이 신뢰 요소(자체 평가 결정 참조) |
| 09-09 07:40 | 관찰 일일 요약을 cron(06:20 UTC)으로 자동화해 worklog에 자동 기록 | 세션이 없어도 docs/12 §2 "매일 확인"이 수행되도록 |

## 설정값 근거 표 ("왜 그 값인가")

| 설정 | 값 | 왜 | 근거(실측·문서) |
|---|---|---|---|
| Flink `state.backend` | hashmap (RocksDB에서 전환) | 키드 상태가 수십 KB인데 RocksDB 네이티브 풀 체크포인트가 MANIFEST(352MB)를 매번 복사 | TM 로컬 db 디렉터리 실측(SST 합 15KB, MANIFEST 352.7MB), canonical savepoint 20KB, 전환 후 17.8KB (docs/09 작업5, docs/10) |
| Flink TM `process.size` | 2g (1g→) | 1g에서 task heap 25.6MiB, Metaspace 230/256MB(90%) → 잡 추가 시 OOM 위험. 2g·managed 0.25에서 task heap 692M | Flink 1.18 메모리 모델 계산(`~/cdc-orderbook-probe/out/flink_memory_model.txt`) |
| Flink `jvm-metaspace.size` | 384m (256→) | 잡 3개 클래스로더. 전환 후 실측 81/384MB | REST 메트릭 |
| Flink `managed.fraction` | 0.25 (0.4→) | hashmap이라 RocksDB용 managed 메모리 불필요, heap으로 돌림 | 동일 |
| Flink 슬롯 | 4 (3→) | CDC 2 + circuit 1 + orderbook 1 | 슬롯 여유 0 실측(docs/09) |
| Flink 재시작 전략 | fixed-delay 20회×30초 (클러스터 기본 3×10s 대신, 잡 코드) | ClickHouse 재시작 실측 24초 > 3×10s 창이면 FAILED로 멈춤. 분 단위 장애를 넘기도록 | 04:23 ClickHouse 재시작 실측 |
| Flink Kafka 시작 오프셋 | committedOffsets(LATEST) (`latest()`→) | savepoint 없이 재시작하면 latest로 점프 → 유실 | 46h 장애 문서(docs/06 이슈2) |
| Flink checkpoint retention | RETAIN_ON_CANCELLATION | 취소 후 복구 가능성 확보(이전엔 삭제) | docs/09 작업5 |
| producer `BATCH_SIZE` | 100 (20→) | MySQL 왕복 횟수 감소. 100은 executemany 1회 부담이 작으면서 초당 수십 건 유입을 1~2회로 처리 | flush 단위테스트 5,000행 1.2ms |
| producer `MAX_BATCHES_PER_FLUSH` | 50 | 1회 flush 상한 5,000행 = 2초 창당 2,500 rows/s. 피크 실측 72.75 msg/s의 34배 여유, 수신 루프 응답성 보호 | docs/08 |
| producer `BUFFER_WARN_ROWS` | 5,000 | 정상 buffer 0~63(dry-run). 5,000이면 약 2분치 피크 적체 = 명백한 포화 신호 | dry-run 실측 |
| producer `MARKETS` | ALL_KRW (REST 조회) | 하드코딩 5개 → 상장 변화 자동 반영 | 사전 검증 287마켓 |
| MySQL EVENT | EVERY 10 MINUTE, LIMIT 40000 (매시 25K→) | 전 코인 유입 약 400만/일 = 시간당 16.7만 > 기존 정리 2.5만/시 → 무한 증가. 10분×40K = 시간당 24만(용량 1.4배). 1회 40K는 2월 사고(50K 일괄) 이하이며 파서 NPE는 수정됨 | dry-run: 2,634,793→2,563,267행 감소 확인, 예외 0 |
| Debezium `tombstones.on.delete` | false | 토픽이 compact가 아니라 tombstone 무용. 삭제 1건당 메시지 2→1 | 체결 토픽 84% delete+tombstone 실측; 적용 후 3,000건 중 tombstone 0 |
| Kafka trade `retention.bytes` | 4GB/파티션 (1GB→) | 전 코인 53 msg/s×658B ≈ 3GB/일, 72h 보존 유지 | 사전 검증 §4 |
| Kafka orderbook 토픽 | 6 파티션, RF2, 24h, 6GB/파티션, min.isr 1, segment 256MB | 파티션 6 = 슬롯 여유·향후 병렬도 2~3 대비. RF2 = 브로커 3에서 단일 장애 허용하되 RF3의 3배 쓰기 회피(호가는 재수집 가능한 스트림). 24h = A안(원본 7일은 ClickHouse가 담당). 6GB = 23GB/일 raw 추정의 파티션 몫 + 여유(zstd 실측 후 실제 ~3GB/일/파티션). min.isr 1 = RF2에서 브로커 1대 다운 시 쓰기 지속 | docs/11 |
| collector 프로듀서 | idempotent, acks=all, zstd, linger 50ms, batch 2000 | 중복 방지, 압축률(실측 2.5배), 50ms는 e2e 예산(2초 배치 창) 대비 무시 가능 | docs/11 |
| collector 재연결 백오프 | 1→30초 지수 | WS 연결 한도 5/s/IP(429 실측) 준수 | 사전 검증 §1-3 |
| Flink orderbook JDBC 배치 | 500건 / 2초 | 258 rows/s에서 파트 생성 분당 ~30개 유지. 1초로 줄이면 파트 2배(머지 부담) — e2e p95 1.9s의 대부분이 이 창, 사용자 결정으로 유지 | docs/11 §4 |
| Flink orderbook 워터마크 | out-of-orderness 5s, idleness 30s | 파티션 6개·키 해시로 항상 유입되나 저유동 파티션 정체 대비 | 코드 주석 |
| ClickHouse `orderbook_raw` | Array(Float64)×4, PARTITION BY toDate(ts), ORDER BY (market, ts), TTL 7일 | 배열이 열 압축에 유리(실측 가격 70배·잔량 15~18배), 일 파티션 = TTL 만료가 파트 드롭으로 끝남, 7일 = A안 | docs/11 |
| ClickHouse `orderbook_1m` 지표 | mid open/close/min/max, spread avg/bp/max, imbalance 1/5/15단, depth15, total, recv lag | 스프레드·깊이·불균형이 L2 분석 표준 지표. 1/5/15단은 count .15 구조 그대로. recv lag는 수집 지연 감시용 | docs/11 |
| ClickHouse 메모리 | 1.75G (1.25→) | 호가 인서트 후 87% 도달. max_server_memory_usage 자동 1.13→1.57GiB. 2G가 아닌 이유: 호스트 swap 4~6GB 사용 중이라 여유 최소화 | 04:19·04:55 실측 |
| ClickHouse system.* TTL | 14일 | 로그 15.9GiB(80%) 점유. 14일이면 사고 분석 창 확보하며 ~1GB 수준 | 정리 후 2.41GiB |
| health_check 적재 지연 임계 | p50 > 60초 (10분 창) | 정상 p50 1.2s, 8월 사고 초기(08-19 18:00)에 p50 3,565s. 60s면 포화 시작 수십 분 내 감지, 정상 변동(max 2.8s)의 20배로 오탐 여유 | docs/08 §2 |
| mem_limit 규칙 | 재기동 후 실사용의 1.5배, 최소 128M, Node 앱은 NODE_OPTIONS 병행 | 스왑 상태 RSS 기준 1.5배는 과소산정(n8n 24분 크래시) | docs/09 작업4 |
| 관찰 지표 수단 | cron + CSV (Prometheus 대신) | Prometheus는 Airflow StatsD만 수집 중. 7일 안에 exporter 구축은 범위 초과, 관찰 뒤 정식 지표계로 승격 | 사전 검증 §2-12 |
| 관찰 기간 | 7일 | 피크(KST 09/22~24시)·주말 저거래 1사이클. 10분 확인은 스모크 테스트 | docs/12 |
| 커밋 규칙 | 트레일러 없음, 비밀값 스캔 후 | 포트폴리오 리포(본인 작업으로 보여야), 공개 리포 | — |

## 사전 검증 요약 (09-08 22:25 ~ 09-09 00:20 UTC) — 상세 `~/cdc-orderbook-probe/REPORT.md`

- KRW 287마켓. 단일 WS 커넥션으로 전 마켓 trade+orderbook.15 동시 구독, 누락·드랍 0. 항상 전체 스냅샷(delta 없음).
- 한도 실측: WS 연결 5/s/IP(429 `too_many_requests`, Retry-After 없음), 구독 메시지 7/s 무에러, 동시 20커넥션 OK, REST orderbook 10/s/IP.
- orderbook.15 전 마켓: 아침(07:28 KST) 153.6 msg/s·158.7KB/s, 오전 피크(09:11 KST) **261.7 msg/s·272.8KB/s**. 와이어(permessage-deflate) 66.9→126KB/s. count별 크기(BTC) .5=432B / .15=1,051B / .30=1,998B.
- trade WS 신규 필드 bap/bas/bbp/bbs(최우선 호가) 발견 → 1·2차에서 ClickHouse까지 적재.
- 미니PC: 디스크 352G→(정리 후)366G 여유, crypto_trades 압축률 3.12, Flink 슬롯 0 여유·task heap 25.6MiB, CPU 제한 없는 컨테이너 전부.
- Oracle: PAYG 계정, A1 2OCPU/12GB = 무료 한도 99.2%, Budget 1 SGD 알림 있음.
- Oracle vs 미니PC 동시 프로브 2회: 건수·드랍·p95(35 vs 33ms) 동일 → 집 회선은 병목 아님.

## 1차·2차 실행 상세 로그 (09-08 22:58 ~ 09-09 02:16 UTC)

## 00. 준비
- 22:58 UTC (07:58 KST) backup/ 에 원본 보관: producer.py, cdc/n8n/icepush/circuit/fds compose, MySQL·ClickHouse DDL(crypto_trades + system.* 6개), docker inspect/images, crontab

## 작업 1. 야간 샘플 예약 (23:12 UTC)
- host crontab 1회성: `0 13 9 9 * /home/calme/cdc-orderbook-probe/night_sample.sh` (KST 22:00). 스크립트가 trade/orderbook.15 프로브 2개를 컨테이너로 띄우고 최대 12분 대기 후 잔여 강제 정리, 마지막에 crontab 자기 제거. 로그 `logs/night_sample.log`.
- 세션 내 후속 리마인더 13:16 UTC 등록(결과 반영용).

## 작업 2. 체결 best bid/ask 4필드 (23:17~23:23 UTC)
- 23:17 SIMPLE 키 실수신 확인: bap/bas/bbp/bbs (simple_keys.py)
- 23:18 producer.py 수정: INSERT 11컬럼, parse_trade에 `_opt()` NULL-safe 파싱 추가 (diff는 backup/producer.py.orig 대비)
- 23:18:15 MySQL `ALTER TABLE crypto_db.crypto_trades ADD COLUMN best_ask_price/best_ask_size/best_bid_price/best_bid_size DECIMAL(20,8) NULL, ALGORITHM=INSTANT` (1초, 2.79M rows)
- 23:18:16 Debezium: connector RUNNING 유지, `cdc` 토픽 offset 20에 ALTER DDL 이벤트 기록, `_schema-history`에 best_ask_price 2건, connect 로그 "Already applied 20 database changes"
- 23:19:28 ClickHouse `ALTER TABLE cdc_pipeline.crypto_trades ADD COLUMN ... Nullable(Float64)` ×4 (sequential_id 뒤). dbt/airflow에 `SELECT *`는 dbt tests 3개뿐(ref(int_ohlcv_1h) 대상, crypto_trades 아님) → 영향 없음
- Flink: CdcEventParser가 필드 화이트리스트 방식, ClickHouseSinks INSERT 컬럼 고정 → **신규 필드는 Kafka까지 도달하나 Flink→ClickHouse 미통과. 잡 재제출 필요 → 금지 범위, 2차 지시로 이관**
- 23:19:28 `docker compose build upbit-producer` (17초), 23:19:45 `up -d --no-deps upbit-producer` → 23:19:47 기동, WS 연결 완료. 재연결 공백: 마지막 STATS 23:19:21 → 신규 수신 23:19:47 (약 26초, 빌드 포함 시 재기동 자체는 2초)
- 23:22 검증: 재기동 후 564행 전부 non-NULL, XRP spread 1~2원(5~10bp), DOGE 1원(83bp). Kafka 이벤트에 best_* 4필드 포함(value 770B, 이전 637B → +133B)

## 작업 3. 8/29 반토막 판별 (23:18~23:30 UTC, 읽기 전용)
- REST `/v1/candles/days` 5코인 × 20일(8/18~9/6, UTC 일봉) 저장 `out/candles/days_5coins.json` (rate limit 헤더 group=candles sec=9)
- source_ts(binlog 시각) 기준 일별 비율은 8/19~8/31 41%~322%로 요동 → 그러나 **upbit_timestamp(거래소 체결시각) 기준으로 재집계하면 전 기간 전 코인 97.5~99.9% 일치** → 유실 아님
- 원인: producer→MySQL 적재 지연. source_ts−upbit_ts p50이 8/19 18:00 UTC 3,565s → 8/22 15:00 UTC **132,933s(36.9h)** 피크 → 8/30 00:00 UTC 정상(10s). 8/31 00~06 UTC 재발(7,265s). 9월에도 시간당 7~12 msg/s 구간에서 p50 60~1,475s 반복
- 메커니즘: producer.py flush()는 2초마다 1회, 1회에 BATCH_SIZE=20행만 INSERT → **최대 10 rows/s 상한**. 8/21~8/22 시장 급증(8/22 05:00 UTC 시간당 261,895건 = 72.75/s)으로 deque 백로그 누적, 이후 8일간 10/s로 배수
- 관련: 8/29 06:45 재연결과 무관(백로그 배수 중), "690K→364K"는 배수 완료 후 정상 수준 복귀
- 파일: out/candles/{ch_daily_5coins,ch_daily_by_upbit_ts,ratio_table,hourly_lag}.json

## 작업 4. 디스크·메모리 위생 (23:18~23:32 UTC)
- 23:18:46 `docker builder prune -f` → 7.016GB (df 95G→90G)
- 23:23:34 미사용 이미지 7개 삭제(목록 out/images_delete_list.txt, 보존 6개 사유 명기) → df 90G→86G. dangling 0
- ClickHouse system.*: 23:23 trace_log MODIFY TTL 14d + DROP 202602/202603 (권한 확인). 23:25~23:28 part_log/metric_log/query_log/asynchronous_metric_log DROP PARTITION 202602~202607 + MODIFY TTL 14d
  - **사고**: MODIFY TTL이 자동 실행한 MATERIALIZE TTL 뮤테이션이 메모리 한도(1.13GiB) 초과로 실패·재시도(metric_log 2.42GiB 요구, query_log 1.23GiB). ClickHouse CPU 345%, 메모리 1.16GiB/1.25GiB. query_log 202606 DROP도 실패(뮤테이션 잠금)
  - 23:31:25 `KILL MUTATION` (3건) → CPU 9.6%, 메모리 849MiB 회복. 202606·202608 파티션 추가 DROP(메타데이터 연산). 남은 데이터 202609만(5테이블 합 ~550MiB), TTL 14d는 메타데이터에 유지(향후 파트 병합 시 적용, ttl_only_drop_parts 아님)
  - 결과: system.* 15.91GiB → 2.41GiB (query_views_log 1.87GiB 미지시라 미처리). /var/lib/clickhouse 20G→8.8G. df 86G→76G
  - config.d/system-logs-ttl.xml 준비만(마운트·재시작 필요 → 보류)
- mem_limit compose 편집 완료(적용은 00:20 UTC 이후): n8n postgres 256M(shared_buffers 128MB라 규칙값 144M 대신 상향), n8n-redis 128M, n8n 416M, worker 432M, icepush-api 128M, circuit api 128M, fds-redis 128M, fds generator/consumer 512M(2월 이후 exited, 사용량 없음), cdc airflow-init 512M(exited, 1회성). my-postgres는 compose 없음(`docker run`) → `docker update` 예정 256M
- fds-generator/consumer: 2026-02-12 00:42 UTC 이후 exited(rc 137), compose `profiles: ["pipeline"]` 게이트 → 상시 부하 없음

## 작업 5. Flink (읽기 전용, 23:20~23:32 UTC)
- 체크포인트 크기 시계열 샘플러 25분 실행(logs/cp_sizes.log)
- 상태 596MB 정체 규명: TM 로컬 RocksDB 디렉터리 실측 — AnomalyDetector subtask1 `MANIFEST-000004` **352.7MB**, SST 15개 합 ~15KB, WAL 0B. subtask2 MANIFEST 176.9MB. Window 연산자 MANIFEST 55.7/39.6MB
  → 키드 상태(5마켓×5 ValueState)는 수십 KB. 체크포인트 크기 = RocksDB MANIFEST(플러시/컴팩션 버전 기록, 체크포인트당 ≈3.3KB 누적, 105,821회) → 정상 누적도 누수도 아닌 **네이티브 풀 체크포인트 방식의 메타데이터 비대**. 상한은 RocksDB max_manifest_file_size 1GB에서 롤오버

## 추가(사용자 승인, 23:44 UTC). Oracle vs 미니PC 수집 지연 동시 비교
- Oracle(ap-chuncheon-1, aarch64)에 임시 `python:3.11-slim`(arm64, 49MB) 컨테이너로 ws_probe.py 실행(websockets 16.0 pip 설치). 미니PC에서도 같은 시각 같은 조건(trade 전 마켓 / orderbook.15 전 마켓, 10분) 실행. 결과 out/oracle_*.json, out/minipc_par_*.json
- TCP/TLS 타이밍(curl, 3회): 미니PC connect 11~16ms / TLS 34~52ms / TTFB 53~71ms, Oracle connect 5.6~7.1ms / TLS 60~80ms / TTFB 72~100ms
- 23:38 docs/08-ingest-lag-incident.md 작성(작업 3 결과). 주의: 09-08 23:19 producer 재생성으로 이전 컨테이너 로그 소실 → 백로그를 로그 대신 ClickHouse(upbit_ts vs source_ts)로 재구성
- 23:50:04 UTC 작업 2 30분 검증 통과: producer WARNING/ERROR 0, STATS received 6,269 / inserted 5,799 / duplicates 470(INSERT IGNORE, 7.5%) / errors 0. MySQL 재기동 후 5,889행 NULL 0(0.000%). 스프레드 평균 BTC 1.48bp·ETH 4.19bp·XRP 5.54bp·SOL 7.21bp·DOGE 83.0bp, crossed(ask≤bid) 0건, 체결가가 BBO 밖인 행 BTC 8건(스냅샷 시점 차이로 추정, 미검증). Flink 체크포인트 105,848 완료/0 실패, health_check 23:10~23:40 4회 success. ClickHouse 5,987행 유입(best_* 컬럼은 전부 NULL — Flink 미통과, 예상대로). 컨테이너: producer 23MiB, mysql 703MiB, clickhouse 920MiB, flink-tm 348MiB
- 23:55 UTC Oracle vs 미니PC 동시 프로브 결과(08:44:52~08:54:54 KST, 10분): trade 10,280 vs 10,279건(17.13 msg/s 동일), orderbook.15 91,066 vs 91,043건(151.8 msg/s), 누락 마켓 0/0, 에러 0/0. 수신지연 p50(recv−tms) trade 27.6 vs 30.9ms, orderbook 24.0 vs 28.0ms. 미니PC NTP 오프셋 −3.8ms 보정 시 31.4/27.8 vs 30.9/28.0 → 차이 없음. 와이어 대역폭 66.6 vs 67.3KB/s. Oracle 임시 컨테이너·이미지·파일 정리 완료(probe-ora-* 0개, python:3.11-slim 삭제)

## 작업 4-4 mem_limit 적용 및 사고 (00:00~00:25 UTC)
- 00:00:59 UTC: 사용자 crontab `0 0 * * * /home/calme/n8n/update-n8n.sh`(n8n 자동 업데이트)가 23:29에 편집해 둔 compose를 먼저 적용해 n8n 4개 컨테이너를 재생성(이미지 digest 307d6065 동일 = 버전 변화 없음, 2.38.1)
- 00:01~00:23 UTC: **n8n-n8n-1 크래시 루프** — exit 134 "FATAL ERROR: Ineffective mark-compacts near heap limit Allocation failed - JavaScript heap out of memory", 약 23초 간격, 재시작 52회. 원인: cgroup 메모리 제한을 V8가 힙 상한으로 환산(416M→약 225MB, 576M 상향 후 실측 312MB)하는데 n8n 메인 프로세스 기동 시 힙 수요가 그 이상. 제한 없던 이전(호스트 15.7GB 기준)에는 발생하지 않음. 미니PC n8n은 CDC 이상거래 Slack/Gmail 알림 워크플로우를 돌리므로 **약 22분간 알림 기능 중단**
- 00:20:08 UTC 예약 스크립트: icepush-api·circuit-connect-api·fds-redis 재생성, my-postgres `docker update` 256M. cdc-* 미접촉
- 재기동 후 실사용이 기존 측정치(스왑 아웃 상태의 RSS)보다 큼: circuit-connect-api 22.8→119MiB(제한 128M의 93%), icepush-api 17→54MiB, n8n 270→380MiB. 00:21:37 circuit 256M·n8n 576M로 상향(docker update + compose)
- 00:23:3x UTC n8n-n8n-1 제한 **원복**(`docker update --memory 0`, compose에서 deploy 블록 제거). 교훈: 스왑이 많은 호스트에서 docker stats RSS 기반 1.5배 규칙은 과소산정; Node 앱은 cgroup 제한이 V8 힙 상한으로 이어지므로 별도 산정 필요
- `docker update --memory 0`은 반영되지 않음(inspect 576M 유지, 크래시 지속 → 재시작 57회까지). 00:25:23 UTC `docker compose up -d n8n`으로 제한 없이 재생성 → 00:25:26 기동, V8 heap_size_limit 4,192MB, 사용 330MiB, 105초간 재시작 0회, "n8n ready" 확인. 크래시 루프 총 지속 00:01~00:25 (약 24분)
- 최종 mem_limit 상태: n8n-worker 432M(사용 234MiB), n8n-postgres 256M(25MiB), n8n-redis 128M(3MiB), icepush-api 128M(54MiB), circuit-connect-api 256M(119MiB), fds-redis 128M(11MiB), my-postgres 256M(15MiB, docker update만 — compose 없음), fds-generator/consumer 512M·airflow-init 512M(compose만, exited), **n8n-n8n-1 제한 없음(원복)**
- 00:11:20~00:21:26 UTC(09:11~09:21 KST, 국내 오전 피크) p95 포함 재측정: trade 53.6 msg/s(아침 07시대 17.1의 3.1배), orderbook.15 261.7 msg/s(151.8의 1.7배), payload 272.8KB/s, 와이어 126KB/s. 수신지연 p95 미니PC 36.4/35.7ms vs Oracle 34.9/32.6ms, p99 126/59 vs 125/59ms(trade p99는 SNAPSHOT 287건 포함으로 오염). 결과 파일 out/*_all_2.json
- 00:55:19 UTC mem_limit 30분 관찰: 비CDC 컨테이너 8개 재시작 0·OOM 0. producer WARNING/ERROR 0(uptime 5,706s, received 28,856 / inserted 26,689 / duplicates 2,166), Flink 체크포인트 105,913 완료/0 실패, health_check 00:10~00:40 4회 success, ClickHouse 최근 10분 2,052행. free: used 6.7GB / swap 6.1GB(작업 시작 시 4.3GB → 00:20 6.9GB → 6.1GB). df 81G 사용(정리 직후 76G에서 +5G, 원인 미추적)

### 2차 실행 (사용자 승인 01:20 UTC: 1·2·3 묶음 — Flink 상향+hashmap, best bid/ask Flink 통과, producer 상한 제거)
- 01:36 미니PC 점검: 필수 정리 없음(df 366G 여유, available 8.0GB). 선택 후보: airflow/logs 3.1G, journal 1.6G(sudo), query_views_log 1.88GiB, MySQL 1.8G
- 01:40 backup/phase2/: flink/src 전체, flink-cdc-job-1.0.0.jar(02-23 빌드), producer.py(1차본), compose(1차본), jobs-before, offsets-before(flink-cdc-consumer 커밋 오프셋 p0 89,214,711/p1 89,181,681/p2 89,212,679, lag 79~96), ch-before
- 코드 변경: CryptoTradeEvent 4필드(Double, null 허용), CdcEventParser parseNullableDecimal, ClickHouseSinks INSERT 17컬럼+setNullableDouble, CdcPipelineJob setStartingOffsets → committedOffsets(LATEST)
- producer.py: flush()를 버퍼 소진까지 반복(MAX_BATCHES_PER_FLUSH=50), STATS에 buffer= 추가, 5,000행 초과 시 1분당 1회 WARNING. compose BATCH_SIZE 20→100
- compose Flink: JM/TM state.backend hashmap, externalized-checkpoint-retention RETAIN_ON_CANCELLATION, TM process.size 2g / jvm-metaspace 384m / managed.fraction 0.25 / slots 4, TM 메모리 제한 1280M→2304M
- 01:41 producer 이미지 재빌드(75.7MB). flush 단위테스트(test_flush.py, 가짜 커서) 4케이스 통과: 5,000행 1회 소진(50배치, 1.2ms), 12,000행은 5,000 상한 후 잔여 유지+경고, 3번째 배치 실패 시 해당 배치 버퍼 앞 복원·순서 보존, 이론 상한 2,500 rows/s(이전 10)
- 01:41 Flink JAR 빌드 시작(docker 멀티스테이지, 로그 logs/flink-build.log)
- 01:42:27 Flink JAR 빌드 완료(42MB, 1분, CryptoTradeEvent에 bestAskPrice 포함 확인)
- 01:42:48 health_check DAG pause. pre-stop: 커밋 오프셋 p0 89,215,036 / p1 89,181,993 / p2 89,212,975, ClickHouse 최근 max trade_id 95,997,125 (source_ts 01:42:49.039)
- 01:42:53 `flink stop --savepointPath` CDC → savepoint-dd0c41-0cb7e3ec0577 (**20KB, 파일 1개**; 네이티브 체크포인트 625MB 대비 → 상태 소량 확정). 01:42:58 circuit → savepoint-f27298-76d8cc7ff926 (116KB)
- 01:43:18 `docker compose up -d flink-jobmanager flink-taskmanager` → TM 9초 만에 등록. 실효 메모리: process 2048M = overhead 205 + metaspace 384 + fw heap 128 + fw offheap 128 + network 146 + managed 365 + **task heap 692M**. slots 4, state.backend hashmap, 컨테이너 제한 2304M
- 01:43:33 circuit 복원(JobID 55988aed…), 01:43:37 CDC 복원(신규 JAR, JobID 9ad0f8de…) → 둘 다 RUNNING, 슬롯 3/4 사용
- 01:44:24 producer 재기동(`up -d --no-deps`, 3초). 이전 컨테이너 최종 STATS 01:43:54 received 43,709 / inserted 40,557 / duplicates 3,152. 신규 STATS에 `buffer=0` 표기, 배치 크기 100
- 01:45:53 검증: CDC 잡 체크포인트 2/2 완료, **state 17.8KB, e2e 74~163ms**(이전 625MB, 1.8~12s), 예외 0. Kafka 오프셋 p0 89,215,159(> pre-stop 89,215,036, lag 16) 등 savepoint 지점부터 연속 소비. ClickHouse: 정지 후 첫 행 trade_id **95,997,126 = pre-stop max + 1 → 유실 0**. 정지 후 394행 중 388행 best_* non-NULL. TM 557MiB/2.25GiB, JM 337MiB
- 01:46:28 health_check unpause. 정지 구간(01:42:40~01:43:10) 69행 = uniqExact 69 → 중복 0. 정지 직후 6행(95,997,126~131)은 구 JAR가 barrier 전 처리해 best_* NULL(정상)
- 02:16:00 30분 검증: 체크포인트 32/32, state 17.8~18.0KB, e2e avg 51ms/max 163ms. Metaspace 81/384MB, Heap 138/822MB. producer 경고 0, buffer=1. health_check 4회 success. ClickHouse 30분 6,565행 best NULL 0, cdc_latency 5.3ms, ingest lag p50 1,670ms/max 4,025ms, 집계 25행. TM 962MiB/2.25GiB, swap 4.1GB
- 문서: docs/10-phase2-flink-producer-upgrade.md 작성

## 3차 — 호가(orderbook) 수집 착수 (09-09 02:30 UTC ~)
- 02:30 사용자 승인: confluent-kafka, 3브로커 유지, KRaft는 실험 뒤. 통합 worklog를 docs/worklog.md로 이전(이전 파일 ~/cdc-orderbook-probe/worklog-phase1.md는 보관)
- 설계: 수집기 `orderbook-collector`(별도 컨테이너, WS orderbook.15 287마켓 → Kafka `upbit.orderbook.v1`, key=market, zstd, idempotent) → Flink `OrderbookJob`(기존 JAR 내 별도 메인, 슬롯 1) → ClickHouse `orderbook_raw`(TTL 7d, Array(Float64) 4개) + `orderbook_1m`(TTL 365d)
- 03:43:45 Kafka 토픽 `upbit.orderbook.v1` 생성: 파티션 6, RF 2, retention.ms 86,400,000(24h), retention.bytes 6,442,450,944(6GB/파티션), min.insync.replicas 1, segment.bytes 256MB. 리더 브로커 2/3/1 분산 확인
- 03:44 ClickHouse `cdc_pipeline.orderbook_raw`(PARTITION BY toDate(ts), ORDER BY (market, ts), TTL 7일, Array(Float64)×4) · `orderbook_1m`(월 파티션, TTL 365일) 생성. DDL 파일 `clickhouse/orderbook.sql`
- 03:45:00 수집기 이미지 빌드(confluent-kafka 2.15.0, websockets 17.1) → 03:45:09 `cdc-orderbook-collector` 기동(compose 서비스 추가, 256M, cdc-network). 코드 `orderbook-collector/collector.py`: 단일 WS, REST로 KRW 마켓 조회, key=market, zstd, idempotent, 지수 백오프(1→30s), STATS 30초(recv/produced/queue/lag p50·p95)
- Flink `com.cdc.pipeline.orderbook.*` 6개 클래스 작성(Event/Parser/Minute/Aggregator/Sinks/Job). 같은 JAR, 별도 메인·컨슈머 그룹 `flink-orderbook-consumer`, 이벤트타임 1분 tumbling(out-of-orderness 5s, idleness 30s), committedOffsets(LATEST)
- 03:47:20 Flink JAR 재빌드(orderbook 클래스 9개 포함, 43.9MB). 03:47:34 `flink run -d -c com.cdc.pipeline.orderbook.OrderbookJob` → JobID 4e47e3d4…, 3잡 RUNNING, 슬롯 4/4
- 03:48:10 첫 검증(제출 36초 후): orderbook_raw 8,897행 / 268마켓 / 15단 배열, recv_lag(rts−tms) 평균 30ms, e2e(ts→flink_ts) 826ms. orderbook_1m 첫 창(03:47, 부분) 259행. BTC 03:47 창: 190 스냅샷, spread 1.73bp, imb5 +0.917. 컨슈머 lag 0, 예외 0. CDC 잡 체크포인트 124/124 무영향. TM 993MiB/2.25GiB, ClickHouse 1.04GiB, 수집기 25MiB
- 04:05 결정 기록: 브로커 3 → 실험 → 1 (결정 표 참조)
- 04:19:00 30분 관찰(03:49~04:19): 유입 257.7 msg/s(464,513건), 재연결 0·발행 실패 0. recv lag p50 30/p95 40ms, e2e(업비트→ClickHouse) p50 849/p95 1,892/max 2,203ms. **orderbook_raw 39.7 B/행 on-disk(압축 14.2배, JSON 대비 26배)** → 원본 0.89GB/일, 7일 6.2GB. Kafka zstd 415 B/msg → RF2 18.5GB/일. orderbook_1m 28창×286마켓 7,824행. Flink 체크포인트 31/31 state 61KB e2e 44ms. CDC 잡 155/155·producer buffer 2·health_check 3회 success 무영향. ClickHouse 메모리 1.087/1.25GiB(87%) 감시 필요. 문서 docs/11-orderbook-launch.md

## ClickHouse 메모리 상향 1.25G → 1.75G (04:22 ~ 04:26 UTC, 사용자 승인)
- 영향 분석: 데이터는 named volume(clickhouse_data) → 무관. 위험은 Flink JDBC 싱크 실패→재시작→at-least-once 중복 → 3잡 savepoint 정지로 회피. Kafka/MySQL/producer/수집기 무영향
- 04:22:50 health_check pause. 04:22:54~57 3잡 savepoint 정지(orderbook 4e47e3-77b40169cc28, circuit 55988a-c9c95b562c74, CDC 9ad0f8-e2b382f3b507). 정지 시 오프셋 backup/phase3/offsets-at-stop.txt
- compose: clickhouse memory 1280M→1792M, `./clickhouse/config.d/system-logs-ttl.xml` 단일 파일 마운트(디렉터리 마운트는 docker_related_config.xml을 가려 listen 설정이 깨지므로 회피)
- 04:23:22 `up -d --no-deps clickhouse` → 04:23:46 기동(24초). max_server_memory_usage **1.57GiB**(이전 1.13). 행 수 대조: 정지 전 대비 crypto_trades +257·orderbook_raw +7,668(정지 직전 싱크 flush분), 그 외 동일. `.inner_id.1453ac1e`(mv_latency_stats 내부) 295,877→295,875(−2, 머지에 의한 것으로 추정, 원본 아님)
- system.* 로그 테이블 rename 없음(config.d TTL과 ALTER TTL 일치 확인: `TTL event_date + toIntervalDay(14)`) → TTL 영속화 완료
- 기존 경고(IPv6 listen 실패)는 재시작 전과 동일한 무해 경고

## 전 코인 체결 확장 준비 + 관찰 전 정비 (04:35 UTC ~, 사용자 승인: MySQL EVENT·Kafka retention·health_check 수정·cron 지표)
- 04:35:31 MySQL `ALTER EVENT cleanup_old_trades`: EVERY 1 HOUR / LIMIT 25000 → **EVERY 10 MINUTE / LIMIT 40000**(처리 용량 60만→240만 행/일). 원본 backup/phase3/mysql-event-before.txt. 테이블 2,745,864행(9/1~)
- 04:35:31 Kafka `cdc.crypto_db.crypto_trades` retention.bytes 1GB → **4GB/파티션**(동적, 무재시작). 근거: 전 코인 53 msg/s×658B ≈ 3GB/일, 72h 유지
- 실측: 최근 24h anomaly_alerts 193건(LARGE_TRADE 38, VOLUME_SURGE 155) — 5코인 기준. 임계값: LARGE_TRADE BTC 5억/ETH 3억/기타 1억, PRICE_SPIKE BTC 2%/기타 3%, VOLUME_SURGE EMA×150, RAPID 비활성
- **발견: n8n 이중 실행** — 미니PC와 Oracle 양쪽 n8n 모두 "CDC Pipeline - Anomaly & Health Monitor" active=true (워크플로우 10개 목록 동일). 알림이 2중으로 나가는 상태. 어느 쪽을 끌지 결정 필요
- Flink 재시작 전략을 잡 코드에 명시(CdcPipelineJob·OrderbookJob: fixedDelay 20회×30초). 다음 재제출 시 적용
- 04:36 producer: `MARKETS=ALL_KRW` 환경변수 시 REST로 KRW 전 마켓 조회(재시도 10회) 후 구독. 이미지 재빌드·import 확인. 재기동은 04:55 관찰 확인 뒤(재시작 간격 분리)
- 04:37 health_check DAG: `expected_jobs=3`, `check_ingest_lag` 태스크 추가(최근 10분 source_ts−upbit_timestamp p50/max), evaluate에서 p50 > 60초면 "Ingest Lag" 알림. DagBag 파싱 오류 0, 태스크 7개
- 04:39 n8n 이중 실행 **실증**: 양쪽 execution_entity 최근 30분 "CDC Pipeline - Anomaly & Health Monitor" 30회/30회, 마지막 실행 04:39:27.026(미니PC) vs .029(Oracle), 성공 28/28. 사용자 결정: **미니PC 쪽 워크플로우만 비활성화**(컨테이너는 유지)
- 04:40:50 `docker exec n8n-n8n-1 n8n update:workflow --id=wSAmy0kCFOuxFCz2 --active=false` → DB active=false 확인(남은 active: My workflow 2, Circuit Connect Daily Log Export v2). CLI 안내대로 04:40:56 미니PC n8n 메인 컨테이너 restart(04:40:57 기동, "n8n ready")
- 04:44:35 검증: 04:41 이후 미니PC n8n CDC 워크플로우 실행 0건, Oracle 4건(매분). 이중 알림 해소. 미니PC n8n 메인 318MiB(제한 없음), worker 281/432MiB
- 04:55:04 ClickHouse 상향 30분 검증: 3잡 RUNNING, 체크포인트 CDC 30/30·orderbook 30/30, 예외 0. 정지 창(04:20~04:30) crypto_trades 2,630행 = uniqExact 2,630(중복 0), orderbook_raw 137,619 = uniqExact(market,ts) 137,619(중복 0), 분당 12.0~15.4K행 연속(04:23~24 재시작 분 포함, 유실 0). ClickHouse **930MiB/1.75GiB(52%)**(이전 87%), MemoryTracking 908MiB. health_check 04:20~04:40 3회 success(적재 지연 태스크 포함). producer buffer 5·errors 0, 수집기 215 msg/s·errors 0. 호스트 swap 3.9GB

## 전 코인 체결 확장 dry-run (04:55 UTC ~)
- 04:55:50 producer 재기동(`MARKETS=ALL_KRW`, BATCH_SIZE 100). 재기동 전 STATS 04:55:22 received 42,735 / inserted 40,696 / duplicates 2,036 / buffer 3, MySQL 2,631,402행(max trade_id 96,040,154)
- 04:55:52 기동: 마켓 **287개** 구독, WS 연결 완료 04:55:53. 첫 STATS(30초): received 585, inserted 547, duplicates 18, errors 0, buffer 20, **18.2 rows/s**(5코인 3.6/s의 5배)
- 1시간 dry-run 모니터 시작(10분 간격): producer buffer/rate, MySQL 증가·정리, Kafka 체결 토픽 유입, Flink CDC 체크포인트·백프레셔, ingest lag, anomaly_alerts 유형별 건수(알림 폭주 판정), ClickHouse·TM 메모리
- 04:58 관찰 지표 수집 스크립트 `scripts/observe/collect_metrics.sh` 작성·테스트(87컬럼, 실행 12초). 체결(5분 행수·마켓·ingest lag p50/p95/max·cdc_latency·flink lag·best NULL), 호가(행수·recv/e2e p50/p95/max·1m), alerts 유형별, ClickHouse 메모리·파트수·용량, producer/collector STATS, Flink 3잡 체크포인트·state·e2e, TM heap/metaspace, Kafka end-offset·lag·디스크, MySQL 행수, 컨테이너 9개 mem/cpu, 호스트 load/mem/swap/df. 출력 `~/pipeline-observation/metrics_5m.csv`. readonly_user가 system.*를 못 읽어 그 둘만 clickhouse-client 사용
- 05:00 crontab `*/5 * * * *` 등록(로그 ~/pipeline-observation/collect.log). **7일 관찰 창 시작 시각은 dry-run 종료·안정 확인 후 기록**
- 05:57:46 dry-run 1시간 종료 **통과**: received 96,545 / inserted 94,325 / duplicates 2,210(2.3%, INSERT IGNORE) / errors 0 / buffer 0~38 / 경고 0. 처리율 25.5~31.2 rows/s(5코인 3.6/s의 7~9배). Flink CDC 체크포인트 93/93, 백프레셔 0ms/s, e2e 27~49ms. ingest lag p50 1.1~1.2s / p95 2.1s / max ≤2.8s(10분 창 6회 모두). ClickHouse 10분당 11.0~19.7K행·239~254마켓. anomaly_alerts 1시간 20건(PRICE_SPIKE 6, LARGE_TRADE 5, VOLUME_SURGE 9; 상위 BTC 6·USDT 5·INJ 3) → 폭주 없음, 임계값 유지. MySQL 2,634,793 → 2,563,267행(10분×40K 정리가 유입을 따라감). health_check 04:50~05:40 6회 success. 메모리 producer 23MiB·mysql 744MiB·connect 734MiB·TM 1,000MiB·ClickHouse 737MiB
- 05:58:25 Debezium `tombstones.on.delete=false` PUT(원본 backup/phase3/connector-config-before.json). 커넥터·태스크 RUNNING 복귀 5초 내. Connect 로그 ERROR 1건 "Exception while closing JDBC connection"은 태스크 재시작 시 MySQL 커넥션 종료 예외(무해, 이후 정상)
- 05:58:53 health_check pause → 3잡 savepoint 정지(circuit a8c89c-d1a92a6c0bdf, orderbook 7d75f2-b78cca6fc6b4, CDC b8a55e-53eaa760d1e7) → 05:59:04 재시작 전략 포함 JAR로 재제출(CDC e1dac99d…, orderbook d9f4786e…, circuit 3e6ee790… 기존 JAR) → 3잡 RUNNING → 05:59:20 unpause. REST 확인: CDC·orderbook "fixed delay 30000ms, #20 attempts", circuit은 클러스터 기본(10s×3, 타 프로젝트 잡이라 미변경)
- 06:0x 커밋 **feb959b** "feat: orderbook pipeline, all-market trades, Flink hashmap/2g TM, producer drain loop, 7-day observation tooling" (26파일 +1,513/−45), 태그 `obs-week1-start`. 커밋 전 스캔: 비밀값·IP·이메일 없음(.env gitignore, 환경변수 참조만). 트레일러 없음(포트폴리오 리포 규칙). 첫 시도는 `flink/target` gitignore 규칙에 add가 막혀 실패·태그 오지정 → 태그 삭제 후 재커밋. 미커밋 잔여: dbt tests/assert_no_long_gaps.sql(기존 diff, 본 작업 아님), png 3개(기존 untracked)
- 06:07:09 재제출 검증: CDC·orderbook 체크포인트 8/8, 예외 0, state 58KB/56KB(287마켓 키). 연속성: 정지 전 max trade_id 96,137,856 → 정지 후 첫 행 96,137,857, 10,516행 = 고유 10,516(중복 0). orderbook_raw 68,143 = 고유 68,143, 분당 11.7~15.7K 연속(05:58~06:01). **tombstone 효과**: 06:05:31 정리 실행 후 p0 최근 3,000건 = create 2,097 / delete 903 / tombstone 0. health_check 05:40·05:50 success. producer buffer 10, errors 0. 지표 CSV 06:05 행: lag p50 1.35s / p95 2.18s, best NULL 0, 호가 e2e p95 1.9s 근방
- 06:10 `git push origin main` + 태그 push
- **06:10 UTC (15:10 KST) — 7일 무변경 관찰 시작.** 종료 예정 09-16 06:10 UTC. 기준 커밋 feb959b(obs-week1-start). 예외 조치는 시각·이유와 함께 이 파일에 기록

## 관찰 중 병행 작업 1: 재생 실험 도구 준비 (06:09 UTC ~, 실험 리포 `~/pipeline-load-lab`, 로컬 git)
- 원칙: 읽기·dry-run만. 프로덕션 토픽·테이블·잡 무접촉. 재생기는 목표 토픽이 `load_test.`로 시작하지 않으면 거부
- `replay/recorder.py`: 시각/오프셋 구간 → JSONL.gz(파티션·오프셋·Kafka ts·key·value 보존, `_meta` 라인). 임시 컨슈머 그룹·미커밋. 스모크: 체결 create 3,000건 0.3초(2.87MB raw), 호가 2,000건 0.4초. 잔여 컨슈머 그룹 0
- `replay/replayer.py`: 고정 속도 / 계단(예 50:300,100:300,200:300) / 원본 간격×배속, loop, 타임스탬프를 현재 기준으로 이동(원본은 `_orig_*` 보존), idempotent+zstd 프로듀서, `--dry-run`
- `replay/measure.py`: 5초 간격 토픽 end-offset·컨슈머 lag·Flink 백프레셔/busy/체크포인트·ClickHouse 적재율·e2e p50/p95/max·컨테이너 자원 → CSV
- `docs/experiment-plan.md`(초안): 격리(load_test.* 토픽·테이블, 실험 전용 TM, CdcPipelineJob 파라미터화 필요), 시나리오 S1 계단/S2 호가/S3 브로커 장애/S4 개선 후, 판정 기준, 코퍼스는 관찰 종료 시 고정
- 리포 커밋 356605c(로컬). GitHub 생성은 미정

## 관찰 중 병행 작업 2: README 갱신 (06:20 UTC ~)
- 갱신 범위: 데이터 소스(287마켓 체결·호가 실측치), 파이프라인 흐름(호가 직접 발행 경로), "2026-09 확장" 절(호가 경로 다이어그램 + 전/후 표), 리소스 배분(30컨테이너, 제한 합 14.3GB·실사용 7.5GB, 브로커·ZK에 3→1·KRaft 예정 명시), 기술 스택(collector·관찰 지표), Phase 9 체크리스트, 이슈 5(적재 지연 36.9h)·이슈 6(n8n 크래시 루프)·이슈 1 후속, 성능 지표(적재 지연·호가 e2e·처리량·체크포인트·200일+·91.97M), 프로젝트 구조(orderbook-collector, orderbook 패키지, orderbook.sql, config.d, observe, docs 07~12·worklog), 감시 유형 2·3 "호가로 확장 예정", 예상 질문 Q2·Q3·Q5·Q6 수정 + Q12(hashmap)·Q13(호가 경로)·Q14(7일 관찰) 추가, 서버 환경
- 원칙: 이 세션에서 실측·문서화된 수치만 사용. 단일 호스트 HA 아님·CDC 소스 인위성은 README에도 명시(면접 답변 프레임과 일치)
- 09-09 07:39 UTC 관찰 일일 요약(자동): 샘플 33, 위반 없음, 체결 lag p95 max 2.18s, 호가 e2e p95 max 5916.0ms, buffer max 63.0, 알림 44, ClickHouse max 0.93GiB → /home/calme/pipeline-observation/digest_2026-09-09.md
- 13:00:01~13:10:04 UTC 야간 프로브(cron, KST 22:00) 완료: 체결 21.3 msg/s(p95 44), 호가 .15 **247.9 msg/s**(p95 367, payload 251 KB/s, 와이어 114 KB/s, recv lag p50 21/p95 27.5 ms). 아침 대비 체결 ×1.95·호가 ×1.61, 오전 피크 대비 호가 ×0.95. 프로브 컨테이너 잔존 0, crontab 항목 자기 제거 확인. 체결 recv lag p95 217ms(1회, 판정 보류)
- 13:16 가동 후 9.5h 실측: orderbook_raw 8,010,381행 평균 234.5 rows/s(KST 시간별 221~246), 39 B/행(14.4배), Kafka 브로커1 2.41GB/536만 msg = 449 B/msg. 용량표 재계산(REPORT.md §4-B, docs/11 §3-B): ClickHouse 원본 7일 5.5GB, Kafka 24h 18GB, A안 상시 ≈ 25GB. docs/08은 사고 문서라 야간 값과 무관 → 미수정
- 09-10 00:25 UTC 관찰 19.5h 중간 점검(수동, 무변경): 234샘플 3잡 RUNNING 234/234, 체크포인트 실패 0, producer 경고 0, best NULL 0, 수집기 발행실패 0·재연결 0. KST 09시 피크에서 체결 69.4 rows/s·buffer max 104·lag p95 2.1s(H1·H2 정상). 체결 lag p50 1.2s 전 시간대 일정, p95 max 2.56s. 호가 e2e p95 중앙값 1.94s, 3s 초과 1샘플(05:59 재제출 창). 알림 273건/19.5h(≈14/h): BTC 80·**USDT 60**·XRP 30 — USDT는 LARGE_TRADE 기본 임계(1억)에 스테이블코인 대량 체결이 걸리는 것으로 추정 → 관찰 뒤 임계값 재조정 후보 1순위(판정은 7일 데이터로). 오늘 체결 1,702,107행 = uniqExact(source_ts,trade_id) → 중복 0
- **H11 해석 보정(계획 변경 아님)**: MySQL 행수 2.63M→3.88M 증가는 정리가 7일 전(5코인 시절, 일 36만 행) 데이터만 지울 수 있어서 생기는 과도기. 287마켓 행이 7일 경과하는 09-16부터 정리(24만/h) > 유입(~8.5만/h)이 되어 정상상태 약 1,400만 행에서 수렴 예상. 09-16 이후에도 증가하면 위반
- 디스크: df 83.5→102.7GB(+19GB/19.5h). Kafka 브로커1 4.6→10.6GB(호가 RF2 + 전 코인 체결). 호가 24h·체결 72h 보존이 차면 정상상태(호가 ≈18GB + 체결 RF3 ≈27GB) → 09-12경 플래토 예상, 여유 366GB 대비 문제 없음

## 관찰 중 분석 1: 유실·중복·병목 직접 지표 (09-10 00:30 ~ 01:10 UTC, 읽기 전용)
- 구간별 지연 분해(체결, 최근 12h, ms): s1 producer→MySQL p50 1,150~1,300 / p95 2,090~2,300 (2초 배치 창) · s2 binlog→Debezium p95 7~10 · s3 Kafka→Flink→싱크 flush p50 1,400~1,800 / p95 3,000~3,180 (JDBC 3초 배치 창; flink_ts는 flush 시각) · s4 ClickHouse insert p95 13~17 · **e2e p50 2.6~3.0s / p95 4.4~4.9s / p99 4.9~5.5s**. 결론: 지연 예산은 배치 창 2개(2s+3s)가 결정하며 자원 병목 아님(producer 활용률 최대 13.8% = 345 rows/s ÷ 2,500)
- 호가(최근 6h): recv p95 33~43ms · Kafka→Flink→flush p95 1,640~1,940 · insert p95 13~15 · **e2e p95 1.7~2.0s / p99 2.0~2.1s**. 워터마크 5s 초과 지연 도착 0%, crossed 0, 15단 미만 0
- 중복: 체결 6h 561,909행 = uniqExact(sequential_id) = uniqExact(source_ts,trade_id) → 0. 호가 3h 2,782,661 = uniqExact(market,ts) → 0
- 커버리지(최근 1h): 체결 285/287마켓(2개는 무거래로 추정), 호가 287/287. ClickHouse 파트 crypto_trades 34·orderbook_raw 13, 머지 0
- **유실 대조(신규 `scripts/observe/reconcile_trades.py`, 업비트 시간봉 vs ClickHouse 체결시각 기준, 287마켓×12h=3,394셀)**: 거래량 가중 비율 **94.74%**(5코인 시절 99.3~99.9%), 95% 미만 셀 531, 시간대별 89.7%(15h·19h UTC)~99.8%. 마켓별 최소 비율 <90인 마켓 134개
- **위치 특정 1**: MySQL vs ClickHouse(09-09 12~24h UTC, upbit_timestamp 기준) 951,279 = 951,279, 3,392셀 불일치 0 → CDC 이후 유실 0
- **위치 특정 2**: REST `/v1/trades/ticks`(거래소 원장) vs ClickHouse sequential_id — BTC 19h: 4,111건 중 CH 3,846, **누락 265(6.4%, 거래량 3.3%)**, 누락 체결의 해당 초 체결수 중앙값 121·최대 218, 166/265가 같은 ms 형제 체결. KERNEL 15h: 50 중 19 누락(거래량 54%), 해당 초 18건 버스트. JTO 20h: 100 중 4. AWE 20h: 2 중 1 누락(단독 체결, 거래량 99.5%). extra(CH에만 있는 것) 0
- producer received = inserted + duplicates(누락 없음), 재연결 0, MySQL sequential_id distinct = count → INSERT IGNORE 탈락 아님. **결론: 업비트 WebSocket `trade` 스트림 자체가 버스트(초당 수십~수백 건) 구간에서 일부 체결을 전달하지 않음(+드물게 단독 체결 누락).** docs/06의 "RAPID_TRADES 100건/10초 천장" 관찰과 같은 현상의 다른 얼굴. 파이프라인 유실이 아니라 **소스 전달 갭**
- 시사점: (1) 5코인 일 단위 대조(99%+)로는 안 보였고 287마켓·시간 단위·원장 대조로 드러남 → 정합성 검증은 소스 원장 기준이어야 함. (2) 해법은 REST ticks 기반 **정합성 보정 배치**(시간별 비율 <99% 셀만 원장 조회 → 누락 sid를 MySQL에 INSERT → CDC로 흘러 ClickHouse까지 자연 보정). 이 보정이 CDC 경로의 실제 쓰임이 되기도 함. 관찰 뒤 후보 목록에 추가(우선순위 높음). (3) H5(유실 없음) 가설은 "MySQL 이후 유실 없음"으로 판정, 소스 갭은 별도 지표(원장 대조 비율)로 관리

## 관찰 중 분석 2: "소스 전달 갭" 결론의 더블체크 (09-10 00:48 UTC ~, 사용자 지적)
- 지적: "MySQL 이후 무결 + producer 앱 카운트 일치"만으로는 앱 밑(websockets 라이브러리 큐, TCP, 서버의 slow-consumer 처리)에서 빠졌을 가능성을 배제 못 함. 특히 producer는 flush(동기 executemany) 동안 이벤트 루프가 멈추므로 "클라이언트가 느려서 서버가 건너뛴" 경우가 남음
- 실험 설계(판별 실험): DB 없이 수신만 하는 **독립 WS 클라이언트**(`~/cdc-orderbook-probe/trade_sid_probe.py`, KRW 287마켓 trade, isOnlyRealtime, max_queue 4096)를 producer와 동시에 20분 붙여 모든 (market, sequential_id, ttms, recv_ms)를 기록 → 같은 창(가장자리 1분 제외)에서 REST `/v1/trades/ticks`(거래소 원장) vs 독립 클라이언트 vs 파이프라인(ClickHouse) 세 방향 sequential_id 집합 대조
- 판정 기준(실험 전에 고정): REST−독립>0 & 독립−파이프라인=0 → 소스 전달 갭(파이프라인 무결). 독립−파이프라인>0 → 우리 producer 유실(앞선 결론 철회, 수신/쓰기 분리 개선 항목화). 둘 다 0 → 버스트 없는 창, 재실험
- 00:48:21 UTC 독립 클라이언트 기동(컨테이너 probe-sid, 프로덕션 무접촉). 종료 후 120초 대기(파이프라인 캐치업) → 대조 스크립트 자동 실행. 결과는 아래에 그대로 기록(결론이 안 나더라도 실험 설계·판정 기준·결과를 남기는 것 자체가 목적)
- 09-10 01:00 UTC 확인 사항(마켓 목록 변화): WS는 명시 코드 목록만 구독. producer·collector는 기동 시 1회 `/v1/market/all` 조회 후 갱신 없음 → 신규 상장은 재기동 전까지 미수집, 상장폐지는 무에러로 0건. 업비트 신호: ticker WS `market_state/is_trading_suspended/delisting_date/market_warning`, `/v1/market/all?is_details=true` market_event. **관찰 뒤 항목 추가**: (1) 수집기 5분 주기 목록 갱신 + 같은 연결에서 재구독(업비트는 최신 구독 메시지로 교체), (2) `markets` 상태 테이블(상장일·폐지일·경보) = 마켓 마스터 CDC 소스, 하류 자동 제외·신규 학습기간, (3) 품질 지표 "목록에 있으나 N시간 0건 마켓". 관찰 종료 시 목록 재조회로 287개 대비 변화 기록
- 01:10:22 UTC 세 방향 대조 결과(창 00:49:21~01:07:21, 독립 클라이언트 60,988건/282마켓): **REST = 독립 클라이언트 (8개 마켓 전부 100% 일치: BTC 2,984=2,984, XRP 2,187, VTHO 7,952, IOST 3,524, KAT 3,627, WAVES 3,003, BLAST 2,534)**, 파이프라인(ClickHouse=MySQL)은 BTC 2,678(**−306, 10.3%**), XRP −100, VTHO −151, IOST −58, KAT −29, WAVES −68, BLAST −58. 판정 기준 2번 해당 → **우리 producer 유실. "소스 전달 갭" 결론(분석 1) 철회**
- producer STATS 창 내 received 58,303 ≈ 독립 클라이언트 54,891(창 경계 차이) → 메시지는 다 받았고 **INSERT 단계에서 버려짐**(duplicates 1,991 증가)
- **근본 원인**: 업비트 `sequential_id`는 전역 유일이 아니라 **마켓별** 유일(= 체결 ms×10,000 + ms 내 순번). 서로 다른 마켓이 같은 ms에 체결하면 sid가 같음. 독립 클라이언트 데이터에서 60,988건 중 sid 중복 2,032건(3.3%) 전부 서로 다른 마켓. MySQL `UNIQUE KEY uk_seq_id (sequential_id)` + `INSERT IGNORE`가 뒤에 온 마켓의 체결을 "중복"으로 버림. 실증: BTC 누락 sid 17890014091030000/1/2가 MySQL에 **KRW-XRP** 행(00:50:10.626)으로 존재. 누락이 버스트 초(중앙값 104 체결/s)에 몰리는 이유 = 같은 ms 충돌 확률이 높아서
- 왜 지금까지 안 보였나: 5코인 시절 ms 충돌 확률이 낮아 거래량 기준 99%+로 가려짐(그래도 producer lifetime "duplicates" 5.3% 중 상당수가 이 충돌이었을 가능성). 287마켓에서 충돌이 급증해 건수 3.3%·BTC 10%로 드러남. 1차 producer STATS "duplicates 2.3%"를 INSERT IGNORE 정상 동작으로 기록한 것은 오판
- 교훈(기록): "소스 문제"로 결론내기 전에 **독립 수신기로 소스를 재현**해야 했음. 사용자의 더블체크 요구가 없었다면 유실을 소스 탓으로 문서화할 뻔함. 유일키 가정(sequential_id 전역 유일)은 문서로 확인된 적 없는 가정이었음
- **판단 실수 기록(사용자 지적으로 확인)**: (1) 1차(09-08 23:50) producer STATS "duplicates 2.3%"를 INSERT IGNORE 정상 동작으로 기록 — 실제로는 마켓 간 sequential_id 충돌로 버려진 체결. (2) 09-10 00:30 원장 대조에서 누락을 보고 독립 수신기로 소스를 재현하지 않은 채 "업비트 WS 미전달"로 결론(분석 1) — 09-10 01:10 세 방향 대조로 철회. 근본 가정 오류: 외부 ID(sequential_id)의 전역 유일성을 문서 확인 없이 스키마(UNIQUE)에 박음
- 01:19:47~01:20:19 UTC **인덱스 교체 실행(승인)**: `ALTER TABLE crypto_db.crypto_trades DROP INDEX uk_seq_id, ADD UNIQUE KEY uk_market_seq (market, sequential_id), ALGORITHM=INPLACE, LOCK=NONE` — 4,044,089행, 32초, 무중단(producer STATS errors 0·재연결 0). Debezium 스키마 변경 토픽에 DDL 기록, 커넥터 RUNNING. 원본 DDL backup/phase3/mysql-crypto_trades-ddl.pre-index-swap.txt. **관찰 기간 예외 조치 1건**(진행 중 유실 3%·BTC 10%)
- 백필 결정(승인): MySQL 경유(REST `/v1/trades/ticks` → 누락 (market, sid) INSERT → CDC → ClickHouse). 부작용 사전 명시: Flink 5분 집계·이상탐지는 processing-time이라 백필 행이 "현재" 창에 섞임 → trade_aggregations 오염(해당 창), 오래된 가격과 현재가 비교로 **PRICE_SPIKE 오탐 가능**(alert의 trade_id로 사후 식별 가능). 적재 지연 알림(p50>60s)은 백필 중 발동하므로 실행 동안 health_check pause. dbt OHLCV는 upbit_timestamp 기준이라 백필 행이 올바른 시간에 귀속됨
- 09-10 01:30 UTC 사용자 질문 "유실·중복·지연 외 DE가 놓쳤을 것" → 코드·설정 재점검으로 7건 등록(관찰 뒤 튜닝 목록, 근거 포함): (1) Kafka 파티션 키가 PK 해시라 마켓 내 순서 미보장 → AnomalyDetector 도착순 비교 오탐 가능 → 키를 market으로; (2) CdcEventParser가 누락 필드를 0.0/"UNKNOWN"으로 채움 → 조용한 손상 경로 → 필수 필드 누락은 실패/격리; (3) ClickHouse 가격·수량 Float64(MySQL은 DECIMAL) → 합계·VWAP 오차, 저가 코인 정밀도 → Decimal 전환; (4) "하루" 정의 불일치(dbt=upbit_ts KST, 품질·dedup 게이트=source_ts UTC, 파티션=source_ts) → 이벤트 시각·KST 통일; (5) 원장 대조가 수동 → REST 7일 창 안에 일일 자동 대조+자동 백필 필요(우선순위 1); (6) Debezium snapshot.mode=initial + 오프셋 유실 시 전체 재스냅샷 → 대량 중복 유입 리스크; (7) 무시된 행 지표 부재(오늘 사고 원인)·최소 권한(producer root, CH default, Kafka 무인증)
- 01:23:14~01:41:03 UTC 백필 dry-run(287마켓, 09-09 05:00 → 09-10 01:20 UTC, 읽기 전용, 1,069초): 원장 1,929,281건 vs MySQL 1,872,608건 → **누락 56,700건(2.94%)**. 마켓별: ETH 6,742(13.2%), BTC 6,432(5.8%), XRP 3,869(3.8%), USDT 2,824(5.3%), SOL 1,301(5.5%), TRUMP 1,175(3.9%). 고거래 마켓일수록 같은 ms 충돌이 잦아 손실률 높음. 결과 CSV out/backfill/backfill_20260910T012357Z_dry.csv
- 교훈 추가: 같은 REST 한도 그룹(`trades/ticks`, 10/s/IP)을 쓰는 작업 2개를 동시에 돌려 429 발생(검증 대조 실패) → 대조는 dry-run 종료 후 429 재시도 포함해 재실행. 원장 API 사용 작업은 직렬화·중앙 페이싱 필요
- 01:52:16 UTC **수정 검증 통과**: 인덱스 교체 후 독립 클라이언트 10분(01:23:01~01:33:01, 31,392건/275마켓) 세 방향 대조 — BTC 807=807=807, XRP 887, VTHO 2,474, BIRB 2,341, UP2 1,496, SOPH 1,865, BLAST 1,379 전부 일치, 초당 최대 105건 버스트 포함 **파이프라인 누락 0**. (첫 시도는 dry-run과의 REST 429 충돌, 두 번째는 대기 루프의 pgrep 자기 매칭으로 지연 — 절차 오류, 결과 무관)

## 유실 복구 백필 (09-10 01:52 UTC ~, MySQL 경유 CDC, 사용자 승인)
- 01:52:53 health_check pause. 기준선: anomaly_alerts 43,846(PRICE_SPIKE 93), MySQL 4,138,185행(max trade_id 98,080,670)
- 01:52:58~02:17:05 **287마켓 구간(09-09 05:00 → 09-10 01:20 UTC) 백필**: 원장 1,929,281 vs MySQL 1,872,608 → 누락 56,700 **전부 INSERT(56,700/56,700)**, 1,447초. 진행 중 producer errors 0·buffer 30, 02:01 시점 ClickHouse에 21,719건 이미 전파(CDC 경로 정상)
- 02:17:49~02:37:07 **5코인 구간(09-03 → 09-09 05:00 UTC) dry-run**: 원장 2,293,250 vs MySQL 2,146,849 → 누락 **146,413(6.38%)**. 5코인만으로도 BTC·XRP·ETH가 같은 ms에 체결하는 빈도가 높아 손실률이 287마켓 구간(2.94%)보다 오히려 큼 → "5코인 시절엔 가려졌다"는 건 거래량 기준 대조가 건수 손실을 숨긴 것이지 손실이 적었던 게 아님. 09-03 이전(2~8월)의 동일 손실은 원장 7일 창 밖이라 복구 불가 — 규모는 producer lifetime duplicates 3.99M/75.9M(5.3%)에서 추정
- 02:37:07 5코인 구간 INSERT 시작(146,413건, 500건 배치·0.5초 간격)
- 02:37:07~02:59:56 5코인 구간 백필: 실제 실행 시 누락 153,769(dry-run 146,413보다 +7,356: 그 사이 MySQL 7일 정리가 09-03 초반 행을 삭제) 전부 INSERT. 03:00 ClickHouse에 백필 행 210,469 = 56,700+153,769 전부 전파. 3잡 RUNNING, producer errors 0. health_check unpause 03:01
- **백필 실수 1건**: 5코인 창 하한을 09-03 00:00으로 잡아 MySQL이 이미 정리한(ClickHouse엔 있는) 09-03 00:00~02:55 행 53,012건을 재삽입 → ClickHouse 중복(trade_id만 다름). 03:03:37~59 lightweight DELETE로 백필 사본 제거 → 09-03 485,030 = uniqExact 485,030. 교훈: 백필 하한은 타깃 기준·소스 보존 경계 고려
- 백필 부작용(예고대로): 01:52~03:02 알림 1,687(PRICE_SPIKE 1,473) vs 전날 7 → 백필 행 연결 1,646건 DELETE(03:04), 잔여 123. trade_aggregations 해당 창 오염은 기록만. Oracle n8n이 그 시간 알림 발송
- 03:05 원장 재대조(09-09 15:00~09-10 03:00, 287마켓 3,393셀): **시간별 전부 100.0%, 95% 미만 0** → 수정+백필 완료 확인
- 문서 docs/13-sequential-id-collision-incident.md 작성. 스크립트 scripts/observe/{backfill_trades,trade_sid_probe}.py 리포에 복사
- 09-10 03:25 UTC README 이슈 7(sequential_id 충돌 유실 사고: 현상·위치 특정·오판과 정정·근본 원인·해결·복구·부작용·교훈) 추가, Phase 9 체크리스트에 예외 조치·관찰 뒤 1순위(reconcile DAG + dbt 테스트) 반영, docs 인덱스에 13번 추가
- 09-10 03:35 UTC 일일 원장 대조(자동, 백필 없음): weighted 80.98% | cells 6813 | <99% 559 | <95% 557 | worst hour 2026-09-09T03 0.0% | fetch_fail 0 | /home/calme/pipeline-observation/reconcile_2026-09-10_03Z.csv
- 09-10 03:35 UTC 일일 원장 대조 cron 등록(`35 6 * * *`, `scripts/observe/daily_reconcile.sh`, 읽기 전용·백필 없음, 06:20 일일 요약과 직렬). 첫 수동 실행(24h 창 09-09 03:00~09-10 03:00) 가중 80.98%는 **확장 전 2시간(03~04 UTC, 5코인만 수집)이 창에 포함된 기간 효과** — 확장 이후 시간대만 보면 아래 검증값. 09-11부터는 창 전체가 287마켓 구간
