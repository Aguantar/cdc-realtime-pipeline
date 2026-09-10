# 11. 호가(orderbook) 수집 착수 — 3차 실행 (2026-09-09)

- 실행 창: 2026-09-09 03:43 ~ 04:19 UTC (KST 12:43 ~ 13:19). 승인: confluent-kafka 의존성, 3브로커 유지(3 → 실험 → 1), KRaft는 실험 뒤.
- 설계 확정값: 수집기 → Kafka 직접 발행(MySQL/Debezium 미경유), count .15, 원본 TTL 7일 + 1분 파생 365일(사전 검증 A안).
- 관련: `docs/worklog.md`(시간순 기록), `~/cdc-orderbook-probe/REPORT.md`(사전 검증), `docs/10`(Flink 재구성).
- 표기: UTC. 3열 = 실측값 / 수행 명령·변경 파일 / 특이사항.

## 1. 구성

```
Upbit WS (orderbook.15, KRW 287마켓, 단일 커넥션)
  → cdc-orderbook-collector (Python, confluent-kafka, zstd, idempotent, key=market)
  → Kafka upbit.orderbook.v1 (6 파티션, RF2, 24h / 6GB·파티션, min.isr 1)
  → Flink OrderbookJob (같은 JAR, 별도 잡, 슬롯 1, hashmap, 이벤트타임 1분 윈도우)
  → ClickHouse cdc_pipeline.orderbook_raw (TTL 7일)  +  cdc_pipeline.orderbook_1m (TTL 365일)
```

| 구성요소 | 실측값·설정 | 파일 | 특이사항 |
|---|---|---|---|
| Kafka 토픽 | `upbit.orderbook.v1` 파티션 6, RF 2, retention.ms 86,400,000, retention.bytes 6,442,450,944, min.insync.replicas 1, segment 256MB, 리더 브로커 2/3/1 | `kafka-topics --create ...` (03:43:45) | 브로커 기본 1GB/파티션이면 5시간분만 남는 문제(사전 검증 §4) 회피 |
| 수집기 | `cdc-orderbook-collector`, python:3.11-slim + websockets 17.1 + confluent-kafka 2.15.0, 256M 제한 | `orderbook-collector/{collector.py,Dockerfile,requirements.txt}`, compose 서비스 | 시작 시 REST로 KRW 마켓 조회(287), 지수 백오프 1→30s(연결 한도 5/s 준수), STATS 30초: recv/produced/deliv_err/queue/lag p50·p95 |
| Flink 잡 | `com.cdc.pipeline.orderbook.OrderbookJob`, 컨슈머 그룹 `flink-orderbook-consumer`, committedOffsets(LATEST), out-of-orderness 5s, idleness 30s, JDBC 배치 500건/2초 | `flink/src/main/java/com/cdc/pipeline/orderbook/*.java` (6개) | JAR 재빌드 후 `flink run -d -c ...OrderbookJob` (03:47:34, JobID 4e47e3d4…) |
| ClickHouse | `orderbook_raw`: PARTITION BY toDate(ts), ORDER BY (market, ts), Array(Float64)×4, TTL 7일. `orderbook_1m`: 월 파티션, TTL 365일 | `clickhouse/orderbook.sql` | 파생지표: mid open/close/min/max, spread·spread_bp avg/max, imbalance 1/5/15단, depth15, total, recv_lag |

## 2. 30분 관찰 (03:49 ~ 04:19 UTC, KST 12:49 ~ 13:19)

| 항목 | 실측값 | 확인 방법 | 특이사항 |
|---|---|---|---|
| 유입 | 수집기 30초 창 226~283 msg/s, 232~290 KB/s payload, 30분 464,513건(257.7/s), 287마켓, 재연결 0, 발행 실패 0, 큐 잔량 ≤16 | 수집기 STATS, `kafka-get-offsets` 차분 | 사전 검증 아침 153.6 / 오전 피크 261.7 msg/s 사이 |
| 수신 지연 | recv−tms p50 **30ms** / p95 **40ms** (수집기 STATS p50 33 / p95 43) | ClickHouse `recv_ts − ts` | Oracle 비교 실험과 동일 수준 |
| e2e 지연 (업비트 → ClickHouse 적재) | p50 **849ms** / p95 1,892ms / max 2,203ms | `flink_ts − ts` | 구성 요소: 수집기 linger 50ms + Flink JDBC 배치 2초 창. 컨슈머 그룹 lag 5,762는 오프셋 커밋이 체크포인트(60초) 단위라 생기는 표시값이며 실제 처리 지연은 위 e2e |
| orderbook_raw 압축 | 30분 **464,651행 / 디스크 18.45MB → 39.7 B/행**, 압축 전 562 B/행(비율 **14.2**), JSON payload 1,050B 대비 **26배**. 컬럼별: ask_prices 70.6배, bid_sizes 15.4, ask_sizes 17.8, ts/recv_ts 1.8 | `system.parts`, `system.parts_columns` 차분 | 연속 스냅샷이 대부분 같은 값을 반복해 LZ4가 잘 눌림. 사전 검증 추정(4.3배)보다 훨씬 유리 |
| Kafka 디스크 | 브로커1(6파티션 중 4개 보유) 30분 +128.5MB → **약 415 B/msg** (zstd, 논리 1,050B 대비 2.5배) | `du -sb` 차분 | 일 258 msg/s 기준 파티션 합 9.2GB/일 × RF2 = **18.5GB/일**, 24h 보존 상시 ≈ 18.5GB (cap 36GB 이내) |
| orderbook_1m | 28창 × 286마켓 = 7,824행, 마켓·분당 평균 55.7 스냅샷 | ClickHouse | BTC 03:47 창: 190 스냅샷, spread 1.73bp, imb5 +0.917 |
| Flink | 체크포인트 31/31 완료, state **61KB**, e2e avg 44ms / max 78ms, 예외 0. TM Heap 230/822MB, 컨테이너 1,006MiB/2.25GiB | REST | 슬롯 4/4 사용(CDC 2 + circuit 1 + orderbook 1) |
| 체결 경로 무영향 | CDC 잡 체크포인트 155/155, producer errors 0·buffer 2, health_check 03:40~04:00 3회 success | REST, 로그, Airflow | |
| 자원 | ClickHouse **1.087/1.25GiB(87%)**, Kafka 브로커 609~640MiB, 수집기 27.6MiB(CPU 15~19%), 호스트 load 2.6, swap 4.1GB, df 79G | `docker stats`, `free`, `df` | ClickHouse 메모리 여유가 가장 작음 → 감시 항목 |

## 3. 용량표 갱신 (실측 기반, count .15, 258 msg/s 기준)

| 저장소 | 사전 검증 추정 | 실측 기반 | 근거 |
|---|---|---|---|
| Kafka 24h 상시 (RF2) | 27GB (raw 13.7GB/일 × 2) | **≈18.5GB** | 415 B/msg zstd 실측 |
| ClickHouse 원본 1일 | 3.2GB (4.3배 가정) | **≈0.89GB** | 39.7 B/행 실측 |
| ClickHouse 원본 7일 상시 | 22GB | **≈6.2GB** | |
| 파생 1분 365일 | ≈1GB | 7,824행/30분 → 137M행/년, 압축 전 ≈ 10GB급 → 압축 후 수 GB 이내(추정, 실측 누적 후 갱신) | |

피크 시간대(261.7 msg/s 실측, 야간 미측)는 ×1.0~1.5 범위. 야간 샘플(13:00 UTC) 반영 후 재계산.

### 3-B. 야간 실측 반영 (13:16 UTC 갱신)

시간대 3점(아침 153.6 / 오전 피크 261.7 / 야간 247.9 msg/s) + 가동 후 9.5h 평균 234.5 rows/s(KST 12:47~22:16, 시간별 221~246). ClickHouse 39 B/행(14.4배), Kafka 449 B/msg(RF2) 실측으로 재계산:

| 저장소 | 상한(2,030만/일) | 중간(1,680만/일) |
|---|---|---|
| ClickHouse 원본 7일 | **5.5 GB** | 4.6 GB |
| Kafka 24h 상시(RF2) | **18 GB** | 15 GB |
| orderbook_1m 연간 | 약 12.3 GB (81.4 B/행 × 41.3만 행/일) | |

A안 상시 점유 ≈ 25 GB(여유 366 GB의 7%). 상세 표는 `~/cdc-orderbook-probe/REPORT.md` §4-B.

## 4. 남은 항목 / 결정 필요 (13:20 UTC 갱신)

1. ~~ClickHouse 메모리 87%~~ → **해결**: 04:23 UTC 1.25G → 1.75G 상향(3잡 savepoint 정지 후 재시작, 유실·중복 0). 이후 52~53%.
2. e2e p95 1.9s의 대부분은 JDBC 배치 창(2초). 1초로 줄이면 파트 수 2배(머지 부담) → **사용자 결정: 유지**.
3. 컨슈머 lag 표시값 오해 방지: 대시보드는 `flink_ts − ts`를 쓸 것(커밋 오프셋 기반 lag 아님).
4. 파생지표 마트(dbt)·Grafana 호가 패널 → **사용자 결정: 재생 실험 뒤**.
5. ~~미커밋~~ → **해결**: feb959b(태그 obs-week1-start)에 포함.
6. 호가 파생지표 선택(imbalance 1/5/15단 등)과 워터마크 5s/idleness 30s는 설계·관행값. 7일 관찰 데이터로 실측 근거 보강 예정.
