# 28. 2층 설계 — 체결 테이블 재설계(A 완료) · 가상 매매 원장 · 케이스 (2026-09-19)

docs/19 §2-2 "DE 본업(구조)" 갈래의 본체. 세 단계이고 A 는 "PK·유니크 키에 시각이 없어 파티션 DROP 을 못 한다"고 인정한 설계 부채(docs/26 §3 정정)의 해소다. 실행은 사용자 결정 뒤, 단계별로.

## A. 체결 테이블 재설계 — 체결 시각 파티션 (근본 해결)
### A-1. 왜
| 근거 | 실측 |
|---|---|
| 보존 삭제가 CDC 로 번짐 | 09-18 이전 체결 토픽의 46% 가 삭제 이벤트(docs/26). 응급 처치(skipped.operations=d·created_at 인덱스)로 막았지만 구조는 그대로 |
| 정석은 파티션 DROP | 행 이벤트가 binlog 에 안 생기고 즉시 끝난다. 못 한 이유 = 유니크 키에 파티션 컬럼이 없어서 |
| created_at 이 아니라 **upbit_timestamp** 로 나누면 된다 | 같은 체결은 언제 다시 넣어도 체결 시각이 같다 → UNIQUE 에 넣어도 gap-fill 재삽입(INSERT IGNORE)이 계속 막힌다. sequential_id 자체가 upbit_timestamp×10,000+n 이라(실측 17897814136350000 = 1789781413635×10000) 유일성 의미도 불변 |

### A-2. 새 DDL (변경점만)
```
PRIMARY KEY (trade_id, upbit_timestamp)                       -- AUTO_INCREMENT 는 첫 컬럼이면 됨
UNIQUE KEY uk_market_seq (market, sequential_id, upbit_timestamp)
PARTITION BY RANGE (upbit_timestamp DIV 86400000) (일 파티션 p20260919 … + p_max)
```
- 컬럼은 그대로(producer INSERT 는 컬럼명으로 쓰므로 변경 없음). `idx_created_at` 은 불필요 → 제거. 정리 EVENT 는 `DELETE` 대신 **`ALTER TABLE … DROP PARTITION`**(7일 지난 파티션) + 다음 날 파티션 선생성(`ADD PARTITION`, 일 1회).
- Debezium: 키가 복합 PK 가 되므로 `message.key.columns=crypto_db.crypto_trades:trade_id` 로 Kafka 키를 trade_id 에 고정(파티션 분배·하류 키 의미 불변). 스키마 변경(DDL)은 `include.schema.changes=true` 라 이력에 남는다. Flink 파서는 컬럼명으로 읽으므로 무변경.

### A-2-1. 스키마 검토표 — 다시 안 바꾸기 위해 (사용자 요구, 09-19)
원칙: ① 시각은 전부 epoch ms(UTC, 시간대 모호성 없음) ② 각 시각은 "누가 찍었나"가 다르고 그 차이가 곧 구간 지연이다 ③ 행의 출처를 행에 남긴다 ④ 키·파티션은 이벤트 시각 기준 ⑤ 추가는 되지만 변경·삭제는 없는 컬럼만.

| 컬럼 | 타입 | 누가 언제 찍나 | 왜 필요한가 | 지금 |
|---|---|---|---|---|
| trade_id | BIGINT AUTO_INCREMENT | MySQL 삽입 순서 | 연속성 검증(정지 실험의 "+1"), Kafka 키 | 있음 |
| market | VARCHAR(20) | 거래소 | 키·파티션 축 | 있음 |
| trade_price / trade_volume / trade_amount | DECIMAL(20,8)/(20,8)/(20,4) | 거래소 | 문자열로 CDC 되는 정밀 소수(decimal.handling.mode=string) | 있음 |
| ask_bid | CHAR(3) | 거래소 | 매수/매도 비율 | 있음(VARCHAR) |
| **upbit_timestamp** | BIGINT ms | **거래소 체결 시각** | 이벤트 시각 = 파티션·유니크 키·규칙 판정의 기준 | 있음 |
| sequential_id | BIGINT | 거래소 | 체결 유일성(= ts×10,000+n) | 있음 |
| **recv_ms** | BIGINT ms | **producer 가 WS 로 받은 순간** | 구간 지연 1 = 거래소→우리 수신(네트워크·거래소 지연). 지금은 못 잰다 | **없음 → 추가** |
| **created_at** | TIMESTAMP(3) | MySQL 삽입 | 구간 지연 2 = 수신→저장(producer 배치 2s). 삽입 시각 기준 보존 정리는 이제 안 함 | 있음 |
| best_ask/bid price·size | DECIMAL | 거래소(체결 시점 최우선 호가) | 체결 시점 스프레드 | 있음 |
| **ingest_source** | ENUM('ws','gapfill','backfill') | producer/도구 | 행 단위 출처. 창 단위 계보(ingest_repairs)와 함께 "이 행이 수리된 것인가"에 즉답. 늦은 이벤트 가드는 그대로 시각 차로 판정 | **없음 → 추가** |
| **stream_type** | ENUM('REALTIME','SNAPSHOT') | 거래소 WS 메시지 | 구독 직후 오는 스냅샷 체결과 실시간을 구분 | **없음 → 추가** |

하류에서 이어지는 시각(ClickHouse): source_ts(binlog = 구간 3 MySQL→Kafka), flink_ts(구간 4 Kafka→Flink→적재), inserted_at. 다섯 시각이 있으면 e2e 를 네 구간으로 쪼개 "어느 구간이 늘었나"를 표로 만들 수 있다(지금의 dq_ingest_daily 는 source_ts − upbit 만 본다 → recv_ms 가 오면 구간별 p95 열 추가).

호환성: 추가 컬럼 3개는 DEFAULT 가 있어 producer·Debezium·Flink 를 한 번에 안 바꿔도 된다 — Debezium 은 새 컬럼을 그대로 실어 보내고, Flink 파서는 모르는 필드를 무시하므로 ClickHouse 쪽 컬럼 추가·파서 수정은 별도 배포로. 이 순서가 "4시스템 동시 변경"을 피하는 길이다.
키·파티션: PK (trade_id, upbit_timestamp), UNIQUE (market, sequential_id, upbit_timestamp), RANGE 일 파티션(upbit_timestamp DIV 86400000), 보존 7일(ClickHouse 가 365일 보관소). 시간대: 서버 UTC, TIMESTAMP 는 UTC 로만 해석.
하지 않기로 한 것: 문자열 시각 컬럼(파싱·시간대 모호), 삽입 시각 파티션(gap-fill 중복), 파생값 컬럼(VWAP 등은 마트에서).

### A-2-2. 추가 검토에서 잡은 점 (사용자 질문 "놓친 게 있나", 09-19)
| 관점 | 점 | 반영 |
|---|---|---|
| 운영 | MySQL 은 파티션을 자동 생성하지 않는다 → 파티션이 없으면 INSERT 실패 | `p_max` (MAXVALUE) 상시 + 매일 다음 날 파티션 `REORGANIZE` 로 선생성. INSERT 는 절대 파티션 때문에 실패하지 않게 |
| CDC | Debezium 은 캡처 대상이 아닌 테이블의 DDL 도 스키마 이력용으로 파싱한다 → 파티션 DDL 을 못 읽으면 커넥터가 멈춘다 | **드라이런에서 ADD/DROP PARTITION 을 실제로 걸고 커넥터 상태·로그 확인** (가장 큰 위험) |
| 성능 | market 이 utf8mb4_0900_ai_ci(대소문자 무시) → 유니크 인덱스가 크고 비교가 느림 | 키 컬럼 `market` 을 `ascii_bin` 으로 |
| 조회 | 대조·백필 도구는 (market, 시각 구간) 으로 읽는데 인덱스가 없어 09-18 dry-run 이 600초 타임아웃 | `KEY idx_market_ts (market, upbit_timestamp)` + 파티션 프루닝 |
| 시각 | recv_ms(우리 시계) − upbit_timestamp(거래소 시계) 는 시계 어긋남에 민감 | 호스트 NTP 상태를 health_check 항목에(음수 지연이 나오면 시계부터) |
| 보존 경계 | 7일 전 파티션 DROP 과 그날 백필이 겹칠 수 있음 | 백필 도구에 보존 하한(7일) — 그보다 오래된 창은 거부 |
| 하류 키 | ClickHouse RMT 정렬 키 (market, source_ts, trade_id) 는 binlog 시각을 품는다. 같은 체결이 다른 binlog 시각으로 두 번 오는 경로는 지금 없다(INSERT IGNORE) | 이벤트 시각 키 (market, upbit_timestamp, sequential_id) 가 더 정직 — **결정 사항으로 남김**(테이블 재생성 필요, A 와 같은 창에 할지) |
| 계약 | 컬럼 이름 `upbit_timestamp` 는 `_ms` 규칙과 어긋남 | 4시스템이 쓰는 이름이라 유지, 문서에 명시 |
| 백업 | MySQL 은 백업 대상이 아니다(7일 완충, 원본은 ClickHouse+백업) | 유지, 명시 |

### A-3. 교체 절차 (정지 없음, 롤백 = 이름 되돌리기)
| 단계 | 내용 | 검증 |
|---|---|---|
| 0 | **드라이런**: 같은 MySQL 에 `crypto_trades_p` 를 만들고 하루치를 복사(sql_log_bin=0) → INSERT IGNORE 재삽입 0건 삽입·DROP/ADD/REORGANIZE PARTITION 소요·**Debezium 이 그 DDL 을 파싱하고 RUNNING 유지** 확인 | 재삽입 0, DROP 초 단위, 커넥터 RUNNING·로그 오류 0 |
| 1 | 새 테이블 생성, `AUTO_INCREMENT` 를 현재 max+1 이상으로 | trade_id 연속성 준비 |
| 2 | **`SET sql_log_bin=0` 세션**에서 7일치 복사(일 단위) — binlog 에 안 남겨 Debezium 이 복사본을 재발행하지 않게 | 복사 행 = 원본 행(파티션별) |
| 3 | `RENAME TABLE crypto_trades TO crypto_trades_old, crypto_trades_p TO crypto_trades` (원자적). producer 는 이름으로 쓰므로 다음 배치부터 새 테이블 | Debezium 이 RENAME DDL 을 처리하고 계속 캡처하는지(스키마 이력·오프셋), Flink 적재 지속, trade_id +1 |
| 4 | 교체 직전 옛 테이블에 들어간 차이분을 `sql_log_bin=0` 으로 새 테이블에 INSERT IGNORE | 원본 uniq = 새 uniq |
| 5 | 정리 EVENT 교체(DELETE → DROP PARTITION), created_at 인덱스 미생성, `skipped.operations=d` 는 무해하므로 유지 | 첫 DROP 실행 시 binlog 행 이벤트 0, 토픽 메시지 = 적재 행 |
| 6 | 대조 DAG 100%, 24h 관찰, 옛 테이블 7일 뒤 DROP | — |
위험: RENAME 을 Debezium 이 "새 테이블 = 캡처 대상" 으로 이어 받지 못하면 재시작·오프셋 확인이 필요하다 → 드라이런(0)에서 include.list 안의 테이블을 RENAME 하는 경우를 먼저 실험한다(작은 더미 테이블로).

### A-3-1. 실행 직전 냉정 점검에서 잡은 것 (09-19, 사용자 요청)
| 점 | 위험 | 반영 |
|---|---|---|
| **trade_id 충돌** | 새 테이블 AUTO_INCREMENT 를 생성 시점 값으로 두면 스왑 직후 새 삽입이 옛 테이블이 그 사이 쓴 번호를 재사용 → 같은 trade_id 두 행(연속성·키 의미 파괴) | 스왑 **직전** `ALTER TABLE … AUTO_INCREMENT = 옛 max + 100,000`. 차이분 복사는 옛 id 를 그대로 옮기므로 충돌 없음 |
| 옛 정리 EVENT | 스왑 뒤에도 `cleanup_old_trades` 가 새 테이블에 created_at DELETE(인덱스 없음 → 풀스캔 59초) | 스왑 전 EVENT DISABLE → 파티션 유지보수 프로시저 `manage_trade_partitions()`(다음 날 파티션 REORGANIZE + 7일 지난 파티션 DROP) 를 EVENT 로 매일 00:05 UTC |
| p_max 잔류 | 미래 시각 체결(시계 오류)이 p_max 에 남아 영원히 안 지워짐 | 유지보수 프로시저가 p_max 행 수를 기록, health_check 에 `p_max > 0` 알림(후속) |
| 롤백 완전성 | 스왑 뒤 새 테이블에 들어간 행이 옛 테이블엔 없다 | 롤백 = 새 테이블 delta(trade_id > 스왑 시점 max) 를 옛 테이블로 INSERT IGNORE → RENAME 되돌림 → EVENT 복원 |
| INSERT…SELECT 잠금 | 복사 중 producer 삽입이 막히나 | binlog ROW + RR 에서 INSERT…SELECT 는 일관 읽기(공유 잠금 없음). 드라이런 75초 동안 적재 지연 불변으로 실측 |
| 도구 | backfill/reconcile 도구가 MySQL 을 created_at 창으로 읽음(09-18 600초 타임아웃) | ✅ 09-19 07:29 A-8: backfill 은 (market, upbit_timestamp) 창(프루닝+idx_market_ts), reconcile 은 MySQL 을 안 읽음(ClickHouse vs REST). producer·backfill 이 새 컬럼을 채움 |
| 파서·하류 | 새 컬럼 3개는 Debezium 이 실어 보내고 Flink 는 이름으로 읽어 무시 | 변경 없음. ClickHouse 컬럼 추가는 A-5 에서 |

### A-4. A-0 드라이런 결과 (09-19 01:35 ~ 01:42 UTC, 같은 MySQL, 캡처 대상 밖 복사본)
| 검증 | 결과 |
|---|---|
| 파티션 경계 | **처음 만든 경계가 날짜와 5일 어긋남**(09-18 → 20719 로 적음, 실제 일 번호 20714) → 코드로 계산해 재생성. 드라이런이 잡은 첫 오류 |
| 복사 | 09-18 하루 2,676,324행, 75초, `sql_log_bin=0`. 전부 p20260918, p_max 0. 그 사이 프로덕션 지연 p95 4.4~4.6s 불변, MySQL 신규 12,328 vs ClickHouse 12,210(창 경계) → **복사본이 CDC 로 새지 않음** |
| 유니크 키 | 같은 체결 1,000행을 created_at 다르게 INSERT IGNORE → **0 삽입**(gap-fill 재삽입 차단 유지) |
| 파티션 DDL | REORGANIZE(p_max→새 날+p_max) 0초, DROP 빈 파티션 0초, **DROP 2.68M 행 0초**(DELETE 59초와 비교). Debezium RUNNING, 로그 오류 0 |
| **RENAME 이어 받기(최대 위험)** | 더미 테이블을 include.list 에 넣고 같은 방식으로 스왑(`RENAME a→a_old, a_p→a`). Debezium 은 WARN 2줄("included → non-included", "non-included → included, schema inconsistency 가능")을 남기고 **계속 캡처** — 스왑 뒤 INSERT 가 같은 토픽에 table=dbz_rename_test 로 도착. 커넥터 RUNNING |
| 정리 | include.list 복원, 더미·복사본 테이블·토픽 삭제, 적재 지속 확인 |
결론: A-3 절차는 그대로 실행 가능. RENAME 의 WARN 은 예상된 것이고, 스왑 직후 스키마 이력이 새 DDL 로 갱신되는지(컬럼 3개 추가분이 이벤트에 실리는지)를 실행 검증 항목에 추가한다.

### A-5. ClickHouse 쪽 재생성 (A-2, 사용자 결정 "전부 포함")
- 왜: 현재 `crypto_trades` 파티션이 binlog 월(toYYYYMM(source_ts))인데 조회는 전부 체결 시각(upbit_timestamp)으로 걸어 **파티션 프루닝이 안 된다**(마트·대조·재계산이 전 월을 훑음). 중복 키도 (market, source_ts, trade_id) 라 binlog 시각을 품는다.
- 새 정의: `ReplacingMergeTree(flink_ts)`, `PARTITION BY toYYYYMM(fromUnixTimestamp64Milli(upbit_timestamp))`, `ORDER BY (market, upbit_timestamp, sequential_id)`, TTL 은 체결 시각 기준 365일. 새 컬럼(recv_ms, ingest_source, stream_type)도 함께.
- 절차: docs/25 와 동일(새 테이블 → 월 단위·일 단위 복사 → MV DETACH → 차이분 → EXCHANGE → 잔여 → ATTACH), 12분 실측 있음. A-1(MySQL) 다음 창에.
- 왜 이렇게 (사용자 질문, 09-19 06:45): ① 파티션 = 체결 시각 월 — 조회가 전부 체결 시각 조건이라 지금(binlog 월)은 프루닝이 안 돼 전 월을 훑고, MySQL 과 기준이 같아진다 ② 정렬 키 = (market, upbit_timestamp, sequential_id) — 체결의 정체성은 (마켓, sequential_id) 이지 binlog 시각이 아니고, (마켓, 시간) 정렬이 조회·분 집계 패턴과 같다 ③ 컬럼 3개는 MySQL 이 이미 보내는 값을 받을 자리(Flink 가 채우는 건 창2). 바꾸지 않는 것: 이름·RMT(flink_ts)·기존 컬럼·dbt·Flink 싱크(창2까지).
- 실무 대비: ClickHouse 표준 원칙 그대로 — 파티션 키 = 필터·삭제 기준(이벤트 시각), 정렬 키 = 낮은 카디널리티 → 시간, RMT 중복 키 = 업무 정체성, 수신·적재 시각은 키가 아닌 컬럼. 처음 표는 "언제 들어왔나"(적재 관점), 이번은 "언제 일어났나"(이벤트 관점).

### A-6. A-1 실행 기록 (09-19 01:50 ~ 06:37 UTC, 정지 없음)
| 단계 | 결과 |
|---|---|
| prepare | 새 테이블(체결 시각 일 파티션 09-11~09-21 + p_max, 새 컬럼 3, ascii_bin, idx_market_ts). 프로시저는 `mysql -e` 가 본문 세미콜론에서 끊겨 실패 → DELIMITER 파일로. 프로시저 테스트를 **복사 중인 새 테이블에 걸어** p20260911 을 지우는 실수(09-10·11 재복사로 복구) |
| copy | 10일 16.86M행, 하루 38~95초, `sql_log_bin=0`, 프로덕션 p95 불변 |
| verify | 처음 규칙이 틀려 두 번 수정(보존 정리는 created_at 기준이라 어느 지난 날이든 src 가 줄 수 있음 → 지난 날 dst ≥ src, 오늘 src ≥ dst). PASS: 09-12 는 src 가 363,094 줄어 있었고(새 테이블이 보유), 오늘 차이분 672,016 |
| swap (06:32:49) | 옛 EVENT DISABLE → AUTO_INCREMENT 119,940,919+100,000 → **RENAME 06:32:51** → 차이분 18초. 새 삽입 첫 id 120,040,919(여유값 이상, 충돌 없음). 스왑 전 오늘 행 old 1,419,753 = new 1,419,753 |
| Debezium | RENAME 에 WARN 2줄(예상), RUNNING 유지, **새 메시지에 recv_ms·ingest_source·stream_type 실림**, ClickHouse 연속성 119,940,780 → +1, 적재 60초 3,311행·p95 4.54s |
| finalize | `manage_trade_partitions_daily` EVENT(매일 00:05 UTC) 등록·1회 실행(p20260920 존재 확인, DROP 대상 없음), `cleanup_old_trades` 삭제. 파티션 8개 활성, p_max 0 |
| Kafka 창1 | `message.key.columns=crypto_db.crypto_trades:market` 적용 → 키가 `{"market":"KRW-…"}`, 커넥터 RUNNING, 적재 지속. (zstd·7일 보존·DLQ 토픽은 02:00 적용) |
남은 확인: 09-20 00:05 첫 자동 유지보수(p20260912 DROP·p20260922 생성), 내일 재정렬률(도착 순 ≠ 이벤트 순)이 5.87% → ~0 인지, `crypto_trades_old` 는 09-26 DROP. 도구(backfill·reconcile 의 MySQL 조회를 upbit_timestamp 창으로)·producer ingest_source 는 후속.

### A-7. A-5 실행 기록 (09-19 06:39 ~ 06:57 UTC, 정지 없음, `scripts/ops/clickhouse-trades-v2-cutover.sh`)
| 단계 | 결과 |
|---|---|
| create (06:39) | `crypto_trades_v2` = RMT(flink_ts), PARTITION BY 체결 시각 월, ORDER BY (market, upbit_timestamp, sequential_id), TTL 체결 시각 +365일, 새 컬럼 recv_ms·ingest_source('ws')·stream_type('REALTIME') |
| copy (06:39~06:48, 9분) | 체결 시각 **일 단위 슬라이스 218일**(02-13~09-19), 114,151,318행. 원본 파티션이 binlog 월이라 upbit_timestamp 조건은 프루닝이 안 돼 슬라이스마다 전체를 훑지만 한 슬라이스 2~3초. 쿼리별 메모리 1.2GB·스레드 2 로 제한(서버 한도 1.75GiB). 복사 중 ClickHouse CPU 164%, 메모리 1.53GiB, 프로덕션 p95 4.5s(복사 전과 같음) |
| verify (06:49~06:55) | ① 월별 원본 count = 새 count: 3~9월 전부 일치, **2월만 −4행** ② 일별 uniqExact(market, sequential_id) 원본 = 새 FINAL count: **218일 중 불일치 0**. 처음 검증은 월별 uniqExact 를 한 쿼리로 돌려 메모리 한도(1.12GiB) 초과 → 일 단위로 나눔 |
| 2월 −4행 | 결함 아님. 같은 체결(trade_id 6·1·2·4,607,449)이 02-21·02-23 재스냅샷으로 source_ts 만 다르게 두 번 들어와 있었고, 옛 키 (market, source_ts, trade_id) 로는 중복이 아니어서 남아 있던 행. 새 키 (market, upbit_timestamp, sequential_id) 가 머지에서 1행(flink_ts 최대)으로 정리 — 키를 업무 정체성으로 바꾼 효과가 첫 검증에서 바로 드러남 |
| cutover (06:56:33) | MV DETACH → 차이분 T1~T2 58,988행(새 = 옛) → **EXCHANGE 06:56:34** → 잔여 63행 → MV ATTACH(max minute 06:56 갱신). Flink 3잡 RUNNING, TM 예외 0, 교체 뒤 60초 적재 3,916행·p95 4.39s |
| 새 컬럼 | 적재 행에 recv_ms=NULL, ingest_source='ws', stream_type='REALTIME'(기본값). Flink 가 채우는 것은 Kafka 창2 |
| 프루닝 실측 | "최근 1시간" count: 옛 표 **39/39 파트·14,010/14,010 그래뉼·1.006s** → 새 표 **5/56 파트·59/14,020 그래뉼·0.103s**(약 10배). 옛 표는 월 파티션은 걸러도 정렬 키에 체결 시각이 없어 그래뉼을 못 건너뛰었다 |
| 남은 것 | 옛 표는 `crypto_trades_v2` 이름으로 보존, **09-26 DROP**(롤백 = EXCHANGE 되돌리기 + 차이분). 같은 날 `crypto_trades_rmt`(09-25)도. 디스크 28%(3.8GiB×3) 문제 없음 |
왜 일 단위 슬라이스인가: 어제 RMT 전환에서 24M 행 한 번에 INSERT 가 1.84GiB 로 서버 한도를 넘겼다(docs/25). 왜 uniqExact 를 일 단위로: 한 달 16M 키의 정확 집계는 쿼리 한도를 넘기고, 근사(uniq)는 "불일치 0" 을 말할 수 없다.

### A-8. 도구 정리 기록 (09-19 07:25 ~ 07:32 UTC, producer 재기동 1회)
| 항목 | 결과 |
|---|---|
| producer | 튜플 끝에 (recv_ms=WS 수신 epoch ms, ingest_source='ws', stream_type=Upbit `st` 없으면 REALTIME). gap-fill 행은 (NULL, 'gapfill', 'REALTIME'). 인덱스 5(upbit_timestamp) 의존 코드는 그대로 |
| backfill 도구 | INSERT 에 (NULL, 'backfill', 'REALTIME'). MySQL 조회는 이미 (market, upbit_timestamp) 창 — A-1 뒤 파티션 프루닝 + idx_market_ts 로 09-18 의 600초 타임아웃 원인 제거. 보존 7일 = REST daysAgo 한도와 같음 |
| reconcile DAG | MySQL 을 읽지 않는다(ClickHouse vs 거래소 REST). 수정 없음 — docs/28 A-3 의 "도구가 created_at 창" 은 backfill 에만 해당했고 그것도 upbit_timestamp 로 이미 바뀌어 있었다(정정) |
| 재기동 | `compose build`(17초) → `up -d --no-deps` 07:29:04~07:29:21, WS 재연결, 기동 gap-fill 5.3초 창 289마켓: 원장 103 / 삽입 23 (전부 ingest_source='gapfill' 로 표시됨). Debezium RUNNING, 새 컬럼 포함 메시지 파싱 실패 0(parseFailures 는 주입 1 그대로) |
| **시각 6개 첫 실측** (07:29~07:32, ws 행 1,788) | 거래소 체결 → 우리 수신(recv_ms − upbit_timestamp) **평균 82ms** / 수신 → MySQL INSERT(created_at − recv_ms) **1,083ms**(배치 간격 1초의 절반 + 실행) / MySQL → Flink 적재(flink_ts − source_ts) **1,697ms**(binlog → Debezium → Kafka → Flink 3초 배치). 이제 e2e 지연을 구간별로 행 단위에서 답한다 — 전엔 source_ts 부터만 보여 "거래소→우리" 82ms 와 "우리 버퍼" 1.1초를 구분 못 했다 |
| SNAPSHOT | 3분간 0건. Upbit 는 요청 시에만 스냅샷을 보내고 우리 구독은 안 한다 — 컬럼은 계약상 보존 |
왜 recv_ms 가 NULL 인 행이 남나: gap-fill·backfill 행은 REST 로 가져와 "수신 시각"이 없다. 억지로 조회 시각을 넣으면 지연 통계가 오염되므로 NULL 이 맞고, ingest_source 가 그 이유를 말한다.

## B. 가상 매매 원장 — CDC 를 제자리에
### B-0. 착수 전 재검증 — "UPDATE·DELETE 는 정말 못 받나" (사용자 요청, 09-19 07:35~07:45 UTC)
사용자: "전에 네 말만 믿었다가 아니었던 적이 많다. 다른 루트로도 확인해라." 방법 = 공식 문서(WebFetch) + **실제 소켓 구독**(producer 이미지의 websockets 로 6~20초 수신) 두 갈래, 둘이 맞을 때만 결론.
| 질문 | 내가 했던 말 | 실측·문서 | 판정 |
|---|---|---|---|
| Upbit 체결에 UPDATE/DELETE 가 있나 | 없다 | trade 스트림 필드: ask_bid, best_*, sequential_id, stream_type, trade_* — 정정·취소 개념 없음. 31건 중 `st` SNAPSHOT 3(구독 직후)·REALTIME 28 | **맞음**. 우리 stream_type 컬럼이 SNAPSHOT 을 실제로 받는다는 것도 확인 |
| Upbit 에 UPDATE 모양 데이터가 전혀 없나 | (말한 적 없지만 그렇게 들릴 수 있었음) | **candle.1m**: 같은 candle_date_time_utc 07:37 이 8번, 07:38 이 4번 반복 수신, 값(trade_price·acc_volume)이 매번 바뀜 — 문서도 "같은 candle_date_time 이 여러 번 전송, 최신을 취하라". **ticker**: 6초에 20건, market_state·delisting_date·is_trading_suspended·market_warning 필드 = 마켓 단위 상태 레코드(DELISTED = 삭제 모양). **orderbook**: 항상 전체 스냅샷(docs/09 실측) = 마켓 단위 상태 | **불완전했음**. 공개 스트림에 키 단위 갱신(캔들·티커·호가)은 있다. 없는 것은 "주문 생애주기"뿐 |
| Upbit 주문 생애주기(진짜 UPDATE/DELETE) | — | private `myOrder`: wait/watch/trade/done/cancel/prevented 를 이벤트 발생 시 푸시(문서). 단 **API 키 + 실제 주문(실돈)** 필요. 테스트넷 없음. 2025-10-27 "주문 테스트 API"는 검증만 하고 "주문이 실제로 생성되지는 않으며 UUID 는 조회·취소에 쓸 수 없다"(문서) | 실돈 금지 규칙 아래서는 **못 받는다** |
| Binance 확장이 "같은 범주(불변 체결)"인가 | 같은 범주 | **depth diff**: 6초 7메시지 1,632 레벨 갱신 중 **762 건이 quantity 0 = 그 가격 레벨 삭제**(문서: 로컬 호가장 관리용, U/u 순번). **kline_1m**: 같은 캔들 4번 갱신, x=false. 즉 키 단위 UPDATE/DELETE 가 공개 스트림에 있다 | **틀렸음**. 체결만 보면 같은 범주지만 Binance 는 증분 호가(삭제 포함)가 있어 범주가 다르다 |
| 실돈 없이 주문 생애주기를 받을 길 | 없다(가상 매매기로 만든다) | **Binance Spot Testnet**: "모든 자금은 가상, 입출금 불가", 사이트 로그인으로 API 키, `executionReport`(NEW/TRADE/CANCELED/REPLACED/EXPIRED…) 를 유저 데이터 스트림으로. 우리 호스트에서 `stream.testnet.binance.vision` 수신 확인. 시세는 프로덕션과 동일 호가(best 81045.90/81045.91 양쪽 일치), 호가 깊이는 더 두껍고(상위5 20.3 vs 4.4 BTC) 체결은 0.9/s vs 13.3/s. 약 월 1회 예고 없이 전체 리셋(미체결·체결 전부 삭제) | **틀렸음**. 거래소 매칭 엔진이 만든 주문 상태 전이를 실돈 없이 받을 수 있다 |
**결론과 B 설계에 미치는 영향**
1. Upbit 로는 주문 생애주기를 실돈 없이 못 받는다 — 이 부분은 원래 판단대로.
2. Binance 테스트넷은 B 의 대안이 된다: 주문은 우리 규칙이 내지만 **체결·상태 전이는 거래소 엔진이 만든다**. 자체 가상 체결(그 분 VWAP)보다 "꾸며낸 데이터" 비판에 강하다. 비용: Binance 상시 사용 결정(지금 규칙 "부하 실험 전용"), API 키 보관, 월 1회 리셋 처리(= 대량 DELETE 의 CDC 실증 기회이기도 함), 테스트넷 유동성이 얇아 체결률이 낮음.
3. 규모 확장 답도 정정: Binance 증분 호가는 "같은 양을 더"가 아니라 "삭제가 있는 상태 스트림"이라 새 범주다. 다만 그건 Kafka 직행 스트리밍이지 CDC 가 아니다.
4. 정정 기록: 09-19 07:35 답변의 "Binance 는 같은 범주" 는 틀렸고, "Upbit 은 불변 체결뿐" 은 체결에 한해 맞다. 두 번 다 "실제로 구독해 보기 전에 말했다"가 원인.
### B-1. 왜
- CDC 의 본래 자리는 회사가 소유한 트랜잭션 DB(docs/26 §6). 시세는 스트리밍+대조가 맞고, 우리에게 없는 것은 **내부 데이터**다. "누구나 받을 수 없는 플래그"는 여기서 나온다.
- 원칙: Faker 없음. 주문은 우리 실시간 시세 위에서 **규칙으로 결정되는 가상 매매**(paper trading)이고 실제 돈은 쓰지 않는다. 데이터가 "가상"인 것은 표시하되 생성 방식은 결정적이라 재현된다.
### B-2. 테이블 (MySQL, 처음부터 체결 시각 파티션 + 시각 포함 키)
| 테이블 | 성격 | 키 | 비고 |
|---|---|---|---|
| `virtual_orders` | 주문(생성·부분체결·취소 = **업데이트 있음**) | (order_id, created_ms) | 상태 전이가 있으므로 CDC 는 **거울(mirror)** 모드 — ClickHouse 는 ReplacingMergeTree(ver, is_deleted) |
| `virtual_fills` | 체결(불변) | (fill_id, filled_ms), UNIQUE(order_id, seq, filled_ms) | 시세 체결과 같은 **보관소** 모드 |
| `virtual_positions` | 포지션 스냅샷(일 1회) | (market, as_of_day) | 집계 검증용 |
- 생성기 `scripts/virtual_trader.py`: 우리 ClickHouse 결합 마트(docs/27)와 실시간 체결을 입력으로 단순 전략 2개(예: 24h 변화율 역추세, 거래대금 급증 추종), 마켓당 일 주문 상한, 체결가는 그 분의 우리 VWAP. 전략은 문서에 고정.
- Debezium: `table.include.list` 에 3개 추가 → 토픽 `cdc.crypto_db.virtual_*`(RF1). 소비: ClickHouse **Kafka 엔진 테이블 + MV**(circuit-connect 와 같은 방식, Flink 잡 추가 없음). orders 는 mirror(is_deleted), fills 는 append.
- 검증: 원장 대조 = MySQL count/uniq vs ClickHouse FINAL(일 1회 dq), 상태 전이 이력이 ClickHouse 에서 재구성되는지.

## C. 케이스 테이블과 내부 신호 (인계 층)
- `cases`(MySQL, mirror): case_id, 근거(alert/rule/market/window), 상태(open→reviewing→closed), 판정(true/false/unknown), 담당, 메모. 규칙 평가의 라벨 보조(거래소 정답이 없는 유동성 플래그의 정답은 여기서 나온다).
- 내부 신호 후보(dbt, docs/27 마트 + 원장): ① 우리 체결이 `volume_over_depth15 ≥ 1` 분에 겹친 비율 ② 주문/체결 비율·취소율 급변 ③ 같은 마켓 양방향 체결(자전 의심) ④ 우리 포지션 마켓의 거래소 지정 겹침. 정의는 케이스 판정으로 검증한 뒤 규칙으로 승격 — 1층과 같은 절차.

## D. 순서와 결정 요청
1. A-0 드라이런(반나절) → 결과 보고 → A 실행(사용자 결정, 정지 없음).
2. B 테이블·생성기·CDC(1일) → 원장 대조 dq.
3. C 케이스 + 신호 2개 → 케이스 판정 루프.
A 가 먼저인 이유: B 의 테이블도 같은 키 설계를 쓰므로 A 에서 절차(복사·RENAME·Debezium 이어 받기)를 검증하면 B 는 그대로 따른다.
