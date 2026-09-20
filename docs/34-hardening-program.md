# 34. 보강 프로그램 — docs/33 의 약점 전부 (2026-09-20 시작)

> 사용자: "말한 모든 내용을 다 보강하자. 철저하게, '왜?'에 답이 되게. Float 도 DE 의 일 아닌가." → 맞다. 저장 층의 숫자 타입은 하류에 주는 계약이고, 정밀도는 원천(DECIMAL·문자열)에 있었는데 Flink 에서 double 로 버린 것이라 DE 책임.
> 원칙: 항목마다 **왜 → 무엇을 → 어떻게 검증** 을 먼저 적고, 실행 뒤 결과를 §N-실행 에 붙인다. 순서는 위험(보안·정확성) → 계약(시간·차원·타입) → 정리.

| # | 항목 | 왜 | 무엇을 | 검증 | 상태 |
|---|---|---|---|---|---|
| 1 | 보안 | 3306·8123·9092 등이 LAN 에 무인증 노출, producer 가 root | ① `sudo scripts/ops/lan-firewall.sh`(사용자) ② Kafka/ZK 를 뺀 서비스 포트를 127.0.0.1 바인딩(재생성 1회씩) ③ producer 전용 MySQL 사용자(INSERT/SELECT crypto_trades) | LAN 의 다른 기기에서 `nc -zv <host> 3306` 실패, producer 적재 지속, 재시작 창 대조 | 진행 |
| 2 | RMT 읽기 FINAL | ReplacingMergeTree 는 "결국" 중복 제거. 읽는 쪽이 보장해야 마트가 재시작 뒤 중복을 안 센다 | stg_trades 에 FINAL, 하류 6모델은 stg 경유(dim_markets·int_reconcile_hourly·int_alert_transitions_recomputed·int_volume_surge_daily·dq_ingest_daily 점검) | dbt build 통과, 중복 주입 뒤 마트 count 불변 실험 | 진행 |
| 3 | 하루 규약 | 마트=KST, dq=UTC 인데 열 이름이 둘 다 day | `day_kst`/`day_utc` 로 이름 통일, docs 규약 한 줄, Grafana·DAG 쿼리 동시 수정 | dbt build + 대시보드 12패널 조회 + DAG 테스트 | 대기 |
| 4 | 차원·사이드 | 코인 키가 거래소마다 다르고 문자열 치환으로 조인, 사이드 의미 반대 | `dim_coins`(coin_id·upbit_market·binance_symbol·base·quote·유효기간), `dim_venues`, 마트 `taker_side`. 환율은 Upbit KRW-USDT 마켓(우리 데이터) → `sig_kimchi_premium` | 조인 유일성 테스트, 김프 값이 공개 지표와 같은 부호·자릿수 | 대기 |
| 5 | Decimal | 금액·수량 Float64 는 회계·대조 등호에 못 쓴다. 원천은 정밀 | Flink 파서 BigDecimal → `setBigDecimal`, ClickHouse crypto_trades/binance_trades price·volume·amount Decimal(20,8)/(24,8) 로 무정지 재생성(EXCHANGE 런북), 마트 파생 타입 확인. 호가 배열은 Float64 유지(파생 지표) — 이유 명시 | 재생성 전후 sum(amount) 등호(Decimal 끼리), 프루닝·적재 지속 | 대기 |
| 6 | 재처리 런북 | 보존은 있는데 절차가 없다 | `scripts/ops/reprocess-day.sh`: 원장(MySQL, 7일) → ClickHouse `mysql()` 함수로 하루 파티션 재생성, Binance 는 Kafka(3일) 재소비 잡, 호가는 Parquet(120일) | 실제 하루를 다시 만들어 대조 100% | 대기 |
| 7 | 죽은 산출물 | anomaly_alerts(09-17 정지)·coin_metadata·trade_aggregations·mart_alert_rate·mart_volume_spike·load_test_* + Grafana 패널 + n8n 빈 폴링 | 인벤토리 표 → 소비자 없는 것 DROP, Grafana 패널 교체, n8n 워크플로 export 를 repo 에 | Grafana 전 패널 데이터 있음, 참조 0 확인 뒤 DROP | 대기 |
| 8 | 테스트·계약 | 새 테이블 테스트 0, exposure·메트릭 정의·데이터 사전 없음 | schema.yml(unique·not_null·accepted_values), exposures.yml, `docs/metrics.md`, `docs/data-catalog.md`, 토픽 JSON 스키마 + 파서 테스트 | dbt test 통과, 스키마 테스트 | 대기 |
| 9 | 마켓 상태 SCD | 폐지·정지를 유실로 오인 | market/all(is_details)+ticker 의 market_state·delisting_date 를 일 1회 스냅샷 → dim_markets SCD | 폐지 마켓이 커버리지 알럿에서 제외 | 대기 |
| 10 | 백업 | Binance 표가 자동 포함 | 제외 목록 + 복원 리허설 재실행 | 리허설 시간·행 수 기록 | 대기 |

## 1-실행 (09-20 04:00 ~ 04:05 UTC)
- 바인딩: `3306`·`8083`·`8123` → 127.0.0.1 (ss 확인). 남은 0.0.0.0: 2181·9092(브로커 재시작 = 호가·Binance 유실 → 정지 창에서), 8081(Flink JM 재생성 = HA 없어 5잡 세이브포인트·재제출 필요 → Decimal 재배포 창과 합침), 3000·8085(인증 있는 UI, 사용자 LAN 접근용 유지). **방화벽(`sudo scripts/ops/lan-firewall.sh`)은 사용자 실행 대기** — 이게 되면 위 넷도 LAN 에서 막힌다.
- producer 전용 MySQL 사용자 `producer`(SELECT·INSERT crypto_trades) — root 제거. 재시작 뒤 연결 성공·기동 gap-fill 29초 창.
- 재생성 순서 Connect → ClickHouse(healthy 20초) → MySQL(12초) → producer. **발견**: MySQL 이 내려간 순간 Debezium 태스크 2개가 `Unexpected error while connecting … BINLOG_FORMAT` 로 FAILED(커넥터는 RUNNING). health_check 가 10분 안에 자동 재시작하지만 런북에선 즉시 `tasks/0/restart` → 2개 RUNNING, binlog 오프셋에서 따라붙음: MySQL 창 6,001행 ⊂ ClickHouse 6,190(유실 0). → MySQL 재시작 런북에 "커넥터 태스크 재시작" 한 줄 추가.

## 2-실행 (09-20 04:05 ~ 04:08 UTC)
- FINAL 추가: stg_trades(뷰)·dim_markets·int_reconcile_hourly·int_alert_transitions_recomputed·int_volume_surge_daily·dq_ingest_daily (RMT 를 읽는 모델 전부, 이제 7/7). 빌드 6~9초/모델 — FINAL 비용은 감당 가능(월 파티션·정렬 키 덕).
- 검증(정정): 첫 판은 두 행을 **한 INSERT** 로 넣어 RMT 가 삽입 시점에 이미 접었고 원본이 1이었다 — "원본 2" 라고 적은 것은 틀린 기록. **별도 INSERT 두 번**(다른 파트)으로 다시: 원본 2 → `stg_trades`(FINAL) **1** → 삭제. 이제 재시작 뒤 머지 전이라도 마트가 중복을 안 센다는 증거가 맞다.
- 실수: 설명 주석을 `{{ config(` 블록 **안**에 넣어 Jinja 가 깨짐(dbt 가 4초 만에 조용히 끝남) → 블록 뒤로 이동. 교훈: dbt 가 너무 빨리 끝나면 성공이 아니라 파싱 실패다.

## 3-실행 (09-20 04:08 ~ 04:15 UTC)
- 규약: **UTC 하루 = `day_utc`**(dq 8·int_volume_surge·mart_trade_orderbook_1m·sig 1), **KST 하루 = `day_kst`, KST 시간 = `hour_kst`**(stg_trades·int_ohlcv_1h/daily·mart_daily_summary). 같은 이름 `day` 를 두 뜻으로 쓰지 않는다. 규약은 stg_trades·dq_reconcile_daily 상단 주석 + 여기.
- 동시 변경: 모델 15, schema/yml 3, 단일 테스트 2, 소비자 = daily_pipeline 리포트 SQL(day_kst·day_utc)·quality_alerts·weekly_digest·품질 대시보드 6패널. 증분 표는 `RENAME COLUMN`(mart) 또는 `--full-refresh`(dq_ingest_daily 는 day 가 정렬 키라 RENAME 불가 — 30일 재계산 6초, dq_orderbook_gaps 23초).
- 검증: dbt run 17/17, 일일 리포트(09-19 KST) 생성, 품질 판정 4/4, 다이제스트 ④ 줄, 대시보드 6패널 실행, DAG 테스트 13/13.
- 이름을 바꾸니 **숨어 있던 테스트 실패 2건**이 드러났다(전엔 ERROR 로 실행조차 안 되던 것):
  - `assert_positive_volume` FAIL: KRW-LINEA 09-11 05시 amount 0. 원인 = **MySQL trade_amount DECIMAL(20,4)** — 가격×수량 < 0.00005 KRW 인 먼지 체결이 0 으로 저장. 30일 64,669행·252마켓(진짜 금액 4e-12~5e-5 KRW). 합계엔 무의미하지만 "정밀도는 원천에 있었다"는 말이 이 열엔 틀렸다 → #5 에서 amount 를 저장하지 않고 Decimal price×volume 으로 계산. 테스트는 그때까지 0.0001 미만 먼지만 허용.
  - `assert_no_long_gaps` FAIL 4: KRW-USDS·RLUSD·USDE(하루 115~304건 스테이블) 3~5시간 공백. 5코인 시절 전제("24시간 거래") 가 287마켓엔 틀렸다 → 하루 1,000건 이상 마켓만. 파이프라인 공백은 커버리지·대조가 잡는다.
- 실수: `quality_alerts` 의 SQL 에서 `WHERE day <` 만 바꾸고 `GROUP BY day`·`ORDER BY day` 를 남겨 404. 정규식으로 SQL 줄 전체를 바꿈.
