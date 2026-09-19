# 28. 2층 설계 — 체결 테이블 재설계 · 가상 매매 원장 · 케이스 (2026-09-19, 결정 대기)

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

## B. 가상 매매 원장 — CDC 를 제자리에
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
