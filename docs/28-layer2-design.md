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

### A-3. 교체 절차 (정지 없음, 롤백 = 이름 되돌리기)
| 단계 | 내용 | 검증 |
|---|---|---|
| 0 | **드라이런**: 같은 MySQL 에 `crypto_trades_p` 를 만들고 하루치를 복사 → INSERT IGNORE 중복 차단·DROP PARTITION·Debezium 이 캡처하지 않음(include.list 밖) 확인 | 유니크 위반 시도 0건 통과, DROP 1초 |
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
