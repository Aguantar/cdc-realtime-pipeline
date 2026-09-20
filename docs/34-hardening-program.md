# 34. 보강 프로그램 — docs/33 의 약점 전부 (2026-09-20 시작)

> 사용자: "말한 모든 내용을 다 보강하자. 철저하게, '왜?'에 답이 되게. Float 도 DE 의 일 아닌가." → 맞다. 저장 층의 숫자 타입은 하류에 주는 계약이고, 정밀도는 원천(DECIMAL·문자열)에 있었는데 Flink 에서 double 로 버린 것이라 DE 책임.
> 원칙: 항목마다 **왜 → 무엇을 → 어떻게 검증** 을 먼저 적고, 실행 뒤 결과를 §N-실행 에 붙인다. 순서는 위험(보안·정확성) → 계약(시간·차원·타입) → 정리.
> 진행 상황(09-20 06:20 UTC): #1~#9 완료·커밋·푸시. 남은 것 #10.

| # | 항목 | 왜 | 무엇을 | 검증 | 상태 |
|---|---|---|---|---|---|
| 1 | 보안 | 3306·8123·9092 등이 LAN 에 무인증 노출, producer 가 root | ① `sudo scripts/ops/lan-firewall.sh`(사용자) ② Kafka/ZK 를 뺀 서비스 포트를 127.0.0.1 바인딩(재생성 1회씩) ③ producer 전용 MySQL 사용자(INSERT/SELECT crypto_trades) | LAN 의 다른 기기에서 `nc -zv <host> 3306` 실패, producer 적재 지속, 재시작 창 대조 | **완료(부분)** — 방화벽은 사용자 sudo 대기 |
| 2 | RMT 읽기 FINAL | ReplacingMergeTree 는 "결국" 중복 제거. 읽는 쪽이 보장해야 마트가 재시작 뒤 중복을 안 센다 | stg_trades 에 FINAL, 하류 6모델은 stg 경유(dim_markets·int_reconcile_hourly·int_alert_transitions_recomputed·int_volume_surge_daily·dq_ingest_daily 점검) | dbt build 통과, 중복 주입 뒤 마트 count 불변 실험 | **완료** 04:08 |
| 3 | 하루 규약 | 마트=KST, dq=UTC 인데 열 이름이 둘 다 day | `day_kst`/`day_utc` 로 이름 통일, docs 규약 한 줄, Grafana·DAG 쿼리 동시 수정 | dbt build + 대시보드 12패널 조회 + DAG 테스트 | **완료** 04:15 |
| 4 | 차원·사이드 | 코인 키가 거래소마다 다르고 문자열 치환으로 조인, 사이드 의미 반대 | `dim_coins`(coin_id·upbit_market·binance_symbol·base·quote·유효기간), `dim_venues`, 마트 `taker_side`. 환율은 Upbit KRW-USDT 마켓(우리 데이터) → `sig_kimchi_premium` | 조인 유일성 테스트, 김프 값이 공개 지표와 같은 부호·자릿수 | **완료** 04:20 |
| 5 | Decimal | 금액·수량 Float64 는 회계·대조 등호에 못 쓴다. 원천은 정밀 | Flink 파서 BigDecimal → `setBigDecimal`, ClickHouse crypto_trades/binance_trades price·volume·amount Decimal(20,8)/(24,8) 로 무정지 재생성(EXCHANGE 런북), 마트 파생 타입 확인. 호가 배열은 Float64 유지(파생 지표) — 이유 명시 | 재생성 전후 sum(amount) 등호(Decimal 끼리), 프루닝·적재 지속 | **완료** 05:19 |
| 6 | 재처리 런북 | 보존은 있는데 절차가 없다 | `scripts/ops/reprocess-day.sh`: 원장(MySQL, 7일) → ClickHouse `mysql()` 함수로 하루 파티션 재생성, Binance 는 Kafka(3일) 재소비 잡, 호가는 Parquet(120일) | 실제 하루를 다시 만들어 대조 100% | **완료** 05:30 |
| 7 | 죽은 산출물 | anomaly_alerts(09-17 정지)·coin_metadata·trade_aggregations·mart_alert_rate·mart_volume_spike·load_test_* + Grafana 패널 + n8n 빈 폴링 | 인벤토리 표 → 소비자 없는 것 DROP, Grafana 패널 교체, n8n 워크플로 export 를 repo 에 | Grafana 전 패널 데이터 있음, 참조 0 확인 뒤 DROP | **완료** 05:40 |
| 8 | 테스트·계약 | 새 테이블 테스트 0, exposure·메트릭 정의·데이터 사전 없음 | schema.yml(unique·not_null·accepted_values), exposures.yml, `docs/36-metrics.md`, 데이터 사전은 `docs/35`, 토픽 JSON 스키마 + 검증기 | dbt test 68/68, 토픽 5종 계약 일치 | **완료** 06:05 |
| 9 | 마켓 상태 SCD | 폐지·정지를 유실로 오인 | **계획 수정**: market_state·delisting_date 는 REST 에 없고 웹소켓 ticker 에만 있다(실측) → 10분 폴러 + `dim_market_state_scd`(SCD2) | 커버리지 판정이 거래불가 마켓 제외, SCD 구간 계약 테스트 | **완료** 06:20 |
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

## 4-실행 (09-20 04:15 ~ 04:20 UTC)
- `binance_symbols`(exchangeInfo 스냅샷, 3,665 심볼·USDT 현물 493) + reconcile_binance 에 일 1회 fetch. `dim_venues` seed(통화·하루 기준·사이드 의미·원장 여부·대조 정답), `coin_alias` seed(MANTRA↔OM — 사람이 검토한 별칭만).
- `dim_coins`: 289 코인, **207 이 두 거래소에 모두**, 별칭 1. 검증 unique(coin_id·upbit_market·binance_symbol) 5/5. 실수 2: ClickHouse LEFT JOIN 은 NULL 대신 '' 를 주므로 coalesce 가 아니라 if(!='') — 첫 빌드에서 BTC 조차 안 붙어 both=1 로 나옴 / `FROM t FINAL AS a` 는 문법 오류, `AS a FINAL` 이 맞음 / LowCardinality 비교는 CAST.
- `taker_side`: Upbit ask_bid 그대로, Binance 는 is_buyer_maker 를 뒤집음(BID=테이커 매수). 1시간 분포 BID 594k / ASK 592k — 반반이라 의미가 통일됐다는 방증.
- 환율: 외부 FX 대신 **Upbit KRW-USDT**(하루 58k 체결, 1,365원) 시간 종가 → `int_fx_usdt_krw_hourly`. 우리 데이터라 조건이 같다.
- `sig_kimchi_premium_hourly`: 어제 "가격은 비교하지 않는다"의 **정정** — 통화·환율·코인 차원이 갖춰지면 비교할 수 있고, 거래소가 다르기 때문에 성립한다. 실측: BTC/ETH/XRP 최근 3시간 −0.10 ~ +0.08%(김프 거의 0 인 날), 196 코인 918행, 중앙값 −0.07%. 극단값 LSK −27.8%·EGLD +38.4% 는 얇은 마켓(체결 ≥10 조건만)이라 조회 시 유동성 필터가 필요 — 그대로 둔 이유: 신호는 원인을 보여야지 숨기면 안 된다.
- `sig_cross_venue_flag_overlap` 을 dim_coins 조인으로 바꿈(문자열 치환 제거) → 1행.

## 5-준비 (중단 지점, 09-20 04:36 UTC 확인) — 코드 변경 전
세션이 여기서 끊겼다. **변경된 파일 없음**(git 작업 트리 깨끗, #4 까지 전부 커밋·푸시 f0aac63). 조사로 알아낸 것:
| 경로 | 지금 | 바꿀 것 |
|---|---|---|
| `CryptoTradeEvent` | `double tradePrice/tradeVolume/tradeAmount` | `BigDecimal` 3개 (getter/setter 포함) |
| `CdcEventParser` | `parseDecimal(data,...)` → double (Debezium 은 `decimal.handling.mode=string` 이라 **원문은 문자열**) | `new BigDecimal(node.asText())` |
| `ClickHouseSinks.rawTradeSink` | `ps.setDouble(4~6, ...)` | `ps.setBigDecimal(...)` |
| `MarketAlertDetector:95` | `double p = e.getTradePrice()` (24h 링·등급 판정) | 판정은 비율 비교라 `doubleValue()` 로 받아도 결과 불변 — **규칙 동등성이 깨지지 않게 여기는 double 유지**하고 이유를 주석에 |
| `BinanceTrade/Parser/Job` | `double price/qty`, `Double.parseDouble(d.get("p").asText())` (원문 문자열) | `BigDecimal` + `setBigDecimal`, `quote_qty` 는 `price.multiply(qty)` |
| ClickHouse `crypto_trades`·`binance_trades` | `Float64` | `Decimal(20,8)` / 금액 `Decimal(24,8)` — EXCHANGE 런북으로 무정지 재생성 |
| MySQL `trade_amount` | `DECIMAL(20,4)` — 먼지 체결 64,669행이 0 (§3-실행에서 발견) | **amount 를 저장·전송하지 않고** ClickHouse 에서 `price*volume` 으로 계산(또는 MySQL 스케일 확대). 저장 안 하는 쪽이 단순 |
순서: ① Flink 코드 + 테스트 → 빌드 ② 새 표 생성·복사·EXCHANGE(체결 1.16억 행, 어제 9분) ③ 잡 재배포 ④ 대조·마트 재빌드 ⑤ 기록. 위험: Flink 재배포 1회(정지 ~50초), ClickHouse 재생성 중 CPU.

## 5-실행 (09-20 04:40 ~ 05:19 UTC) — 금액·수량 Decimal
### 무엇을
| 층 | 전 | 후 | 왜 |
|---|---|---|---|
| Flink 모델·파서·싱크 | `double` ← `parseDecimal` ← Debezium **문자열** | `BigDecimal` ← 문자열 그대로, `setBigDecimal` | 원천에 있던 정밀도를 우리가 버리고 있었다 |
| `trade_amount` / `quote_qty` | MySQL DECIMAL(20,4) 값을 그대로 실어 나름 | **price × volume 계산**(스케일 8+8=16) | MySQL 스케일 4 라 먼지 체결이 0(30일 64,669행). 계산하면 "amount = 정의" 가 항상 성립 |
| ClickHouse `crypto_trades`·`binance_trades` | Float64 | `Decimal(20,8)` / 금액 `Decimal(38,16)` | 정수부 22자리 vs 전 기간 합계 15자리 = 여유 7자리 |
| `MarketAlertDetector` | double | **double 유지** | 판정이 비율 비교라 결과가 같고, `ValueState<double[]>` 타입을 바꾸면 세이브포인트 복원이 깨진다. 테스트 9/9 무수정 통과가 그 증거 |
| 호가(`orderbook_raw`·`binance_orderbook_raw`) | Float64 | **Float64 유지** | 합산되는 금액이 아니라 비율 지표(mid·spread_bp·imbalance)의 재료. Array(Decimal) 은 초당 300 스냅샷에서 비용만 는다 |
| `market_alerts` | Float64 | **Float64 유지** | 퍼센트·판정 근거이고 값이 탐지기 링에서 나온다 |
| dbt 비율 7모델 | `if(v>0, a/v, 0)` | `toFloat64` + `nullIf` | **Decimal 나눗셈은 분모 0 에서 예외를 던져 모델이 죽는다**. `if` 가드는 ClickHouse 가 양쪽 분기를 다 계산해 무용(09-20 실측) |
**타입 규약(이 프로젝트의 규칙)**: 금액·수량 = Decimal, 비율·파생지표 = Float64.

### 발견 — `CAST(Float64 → Decimal)` 은 반올림이 아니라 **버림**
1차 복사 검증에서 행수·수량합은 완전히 일치했는데 **월별 금액합만 2e-8 작게** 나왔다. 행 단위로 추적하니 KRW-PEPE 한 행에서 42.86 KRW 차이, 그 차이가 정확히 `volume × 1e-8`.
원인: Float64 `0.00518` 의 실제 비트값은 `0.005179999…` 이고 `CAST`/`toDecimal64`/`accurateCast`/`CAST(round(x,8))` 가 **전부 버림**이라 `0.00517999` 가 됐다.
해법: `toDecimal128(toString(x), 8)` — `toString` 은 그 double 로 되돌아가는 **최단 십진 표기**라 원본 DECIMAL(20,8) 을 복원한다. MySQL 원본과 대조: `4285662548.26254840`, `22199732.0000000007120000` 정확히 일치. 지수 표기(`1e-8`·`3.2e-7`)도 그대로 파싱.
**교훈: 타입 변환 검증은 행수가 아니라 합계로 한다.** 행수만 봤으면 통과했다.

### 절차와 실측
| 단계 | 결과 |
|---|---|
| 빌드 | 테스트 19/19(먼지 체결 회귀 테스트 추가). 탐지기 9/9 무수정 통과 = 판정 불변 |
| 복사 | 219일·116,900,045행, 누락 0·불일치 0, **행수·수량합·금액합 전부 정확히 일치**(vol_rel_diff 0, amt_ratio 1). 프로덕션 p95 불변 |
| 창 (05:08:20~05:09:46, **86초**) | 두 잡 세이브포인트 정지 → 차이분 8,911(Upbit)·8,826(Binance) → EXCHANGE ×2 → 새 JAR → 복원. 5잡 RUNNING·실패 0·슬롯 8/8 |
| 연속성 | 재기동 구간 MySQL 2,969 = ClickHouse 2,969 |
| 새 행 | amount ≠ price×volume 인 행 **0/2,715**. 먼지 체결 amount=0 **0건**(전엔 하루 수천) |
| 하류 | dbt run 24/24, test 41/41. 분 마트도 Decimal 로 12일 재생성(행수 기록과 일치) |
| **층간 일관성** | 09-19 금액합: 원천 `2534256463850.60064504323932` = 마트 `2534256463850.60064504323932` — 소수 20자리까지 동일. Float64 였다면 마지막 자리가 달랐다 |
| 테스트 원복 | #3 에서 먼지 때문에 느슨하게 뒀던 `assert_positive_volume` 을 원래대로(수량>0 이면 금액>0) → PASS |
| 롤백 | 옛 Float64 표를 `crypto_trades_float_bak`(3.95GiB)·`binance_trades_float_bak`(128MiB) 로 보관. 7일 뒤 DROP |

### 실수 2건
- **돌고 있는 스크립트를 편집**해 복사 프로세스가 마지막 줄에서 죽었다(bash 는 파일을 나눠 읽는다). 09-18 에 기록한 실수의 반복 — 그때 남긴 규칙을 내가 안 지켰다. 다행히 루프는 끝난 뒤라 데이터는 온전했고 일 단위 전수로 확인했다.
- 마트 재생성에서 `--vars '{mart_from: 2026-09-09}'` 처럼 **따옴표 없이** 날짜를 넘겨 YAML 이 `datetime.date` 로 파싱 → 모델의 문자열 슬라이싱이 터졌다. 12번 전부 실패했는데 grep 패턴이 좁아 못 봤고, 행수가 그대로인 것을 "성공"으로 읽었다. → 오늘 스스로 적은 규칙("출력이 비어 있으면 실행이 안 된 것부터 의심")을 적용해 잡음.

## 6-실행 (09-20 05:20 ~ 05:30 UTC) — 재처리 런북
### 설계 판단: 왜 "Kafka 재소비 + 같은 Flink 잡" 인가
| 후보 | 왜 안 골랐나 |
|---|---|
| MySQL 원장에서 SQL 로 다시 만들기 | 파서의 **두 번째 구현**이 생긴다. 오늘 Decimal 전환 같은 변경이 한쪽에만 반영되면 조용히 드리프트한다. 그리고 MySQL 보존도 7일이라 범위가 더 넓지도 않다 |
| ClickHouse 안에서 컬럼만 다시 계산 | 파싱·DLQ·계약 검증을 건너뛴다. "파이프라인이 만든 값"이 아니게 된다 |
| **Kafka 재소비 (채택)** | 같은 잡·같은 파서 → 변환 로직이 하나. ReplacingMergeTree 가 키로 접고 재처리분(flink_ts 큼)이 이겨 **멱등**. 슬롯은 부하 실험용 lab TaskManager(2슬롯)를 잠깐 빌린다 |
구현: `CdcPipelineJob` 에 `CDC_START_TS_MS`/`CDC_END_TS_MS` 추가 → `setBounded` 로 **잡이 스스로 끝난다**(배치처럼). 알럿은 끈다(켜면 같은 전이가 두 번 생겨 동등성 판정이 오염).

### 발견: Kafka 구간은 "도착 시각", 우리가 원하는 창은 "체결 시각"
1차 실행(09-19 10:00~11:00)에서 재삽입이 **29행 모자랐다**. 전부 10:59:59 에 체결됐는데 producer 배치가 경계를 넘겨 11:00:00.46 에 binlog 에 찍힌 행.
→ Kafka 는 `[시작−여유, 끝+여유]`(기본 10분)로 읽고, **커버리지를 직접 잰다**: "체결 시각이 창 안인데 flink_ts 가 잡 시작보다 이른 행" = 재처리가 못 덮은 행. 2차 실행에서 **0**.
백필처럼 몇 시간 늦게 도착한 행이 있으면 이 값이 0 이 아니고, `REPROCESS_MARGIN_MIN` 을 늘려 다시 돌리면 된다.

### 실측 (1시간 창, 121,236행)
| 항목 | 결과 |
|---|---|
| 소요 | 전체 31초 (잡 20초) |
| 멱등 | FINAL 행수·금액합·수량합·고유키 **네 값 모두 재처리 전과 완전히 동일** |
| 커버리지 | 재처리 못 받은 행 **0** |
| raw | 121,236 → 343,218 (2회 재처리분). FINAL 은 그대로 — RMT 가 접는다. 남은 중복 221,982행은 백그라운드 머지가 정리(FINAL 로 읽는 규칙은 #2 에서 세움) |
| 프로덕션 영향 | 별도 group.id·별도 TaskManager. 프로덕션 5잡 무영향. MV 는 분리했다가 재부착(지연 통계 이중 집계 방지) |

### 보존 경계 (정직하게) — `reprocess-day.sh paths`
- **Upbit 체결 7일 밖은 소스에서 되살릴 수 없다.** Kafka 7일 · MySQL 파티션 7일 · 거래소 REST trades/ticks 7일이 **전부 같은 경계**. 남는 건 ClickHouse 백업 복원(값 그대로)이나 일봉 수준 집계뿐.
- 호가: raw 7일, **Parquet 120일**(09-20 검증: 09-17 아카이브 16,333,731행 = 당시 표와 정확히 동일), 파생 1분은 365일이라 지표는 복구 불필요.
- Binance 체결: 토픽 3일. 시세라 원장 의무 없음.
- 한계 한 줄: 재처리한 구간은 `flink_ts` 가 "다시 적재한 시각"이 된다 → 그 구간의 e2e 지연 지표는 원래 값이 아니다(대조·정합성 지표는 영향 없음).

## 7-실행 (09-20 05:32 ~ 05:40 UTC) — 죽은 산출물
상세는 **docs/35 데이터 인벤토리**. 요약:
- 표 8개 삭제(48.1 MiB): `trade_aggregations`·`mart_alert_rate`·`mart_volume_spike`·`coin_metadata`·`load_test_*` 4. 전부 **참조 0 을 먼저 확인**하고 지웠다.
- **죽은 표보다 죽은 참조가 위험했다**: `sync-annotations.sh` 가 매분 cron 으로 09-17 이후 빈 결과를 돌고 있었고, `collect_metrics.sh` 의 알림 4열은 계속 0, Grafana 주석 쿼리도 죽은 표를 봤다 → 전부 살아 있는 `market_alerts`(v2 전이)로 교체하고 동작 확인(24시간 149전이·승급 88·강등 61).
- **n8n 알림 워크플로 3개가 전부 비활성**임을 발견. README 가 "n8n 매분 실시간 알림"이라고 말해 온 것이 09-17 이후 사실이 아니었다 → 문서를 사실과 맞추고(알림은 Airflow 일원화) 정의는 `n8n/workflows/` 에 비밀값 마스킹해 보관. 되살리지 않는 이유: Airflow 가 재시도·의존·이력을 주고 중복이다(docs/33 §2).
- 한시 보관 4종(15.8 GiB)은 **삭제 예정일을 표로 못 박았다**(09-25·09-26·09-27). "나중에 지우자"는 안 지켜진다는 것이 오늘 6종으로 증명됐다.

## 8-실행 (09-20 05:42 ~ 06:05 UTC) — 테스트·계약

### 왜 지금인가
#4·#5 에서 모델과 타입을 크게 바꿨다. 바꾼 뒤에도 **아무 테스트도 안 걸려 있는 모델이 9개**였다 —
즉 "틀려도 아무도 안 알려주는 표"가 9개 있었다는 뜻이다. 그리고 #7 에서 죽은 참조를 찾다가,
누가 이 표를 쓰는지 적어 둔 곳이 없어 일일이 grep 해야 했다. 테스트·exposure·지표 정의는
셋 다 같은 질문("이 값을 믿어도 되나, 깨지면 누가 아픈가")에 대한 답이라 한 번에 묶었다.

### 무엇을 했나
| 갈래 | 내용 | 왜 |
|---|---|---|
| 모델 테스트 | 테스트 0 이던 모델 9개에 not_null·unique·accepted_values 부여 → 열 테스트 보유 모델 13 → 22, 데이터 테스트 **68개** | `dim_market_flag_scd`·`int_fx_usdt_krw_hourly`·`int_venue_hourly_close`·`mart_daily_summary`·`dq_binance_reconcile_daily`·`dq_ledger_daily`·`dq_repairs_daily`·`sig_kimchi_premium_hourly`·`sig_cross_venue_flag_overlap` |
| 입도 계약 | `assert_repairs_daily_grain` 단일 테스트 신설 | 이 표의 한 행 = (하루, 이유) 다. `day_utc` 단독 unique 는 쓸 수 없다(하루에 이유가 여럿이면 정상). dbt 기본 unique 로는 복합키를 못 걸어서 단일 테스트로 |
| 패키지 | `dbt_utils.accepted_range` 를 쓰려다 **되돌림** | dbt_utils 가 설치돼 있지 않았다. 테스트 하나 때문에 패키지 의존성을 새로 들이지 않는다 — 같은 계약을 6줄 SQL 로 쓸 수 있다 |
| exposure | 5개 추가(일일 리포트 Slack·품질 SLO 알럿·주간 다이제스트·교차 거래소 분석·체결×호가 마트) → 총 7 | "이 표를 지우면 누가 아픈가"를 코드에 적어 두는 자리. #7 에서 이게 없어서 grep 으로 찾았다 |
| 지표 정의 | **`docs/36-metrics.md`** 신설 — 7절, 모든 지표를 수식·단위·산출 위치·함정으로 | 같은 이름이 층마다 다른 뜻이면 협업이 깨진다. 특히 `taker_side`(거래소마다 반대), `premium_pct`(분모가 무엇인지), e2e 지연의 여섯 타임스탬프 |
| 토픽 계약 | **`schemas/`** 신설 — README + JSON Schema 5종(Upbit 체결 CDC·Upbit 호가·Binance 체결·Binance 호가·원장 주문) | Debezium 을 `schemas.enable=false` 로 쓰고 있어 **메시지 안에 스키마가 없다**. 09-19 에 열을 하나 늘렸을 때 안 깨진 건 파서가 이름 기반이라 **운이 좋았던 것**이지 보장이 아니었다 |
| 계약 검증기 | **`scripts/ops/validate-topic-schemas.py`** — 의존성 없는 JSON Schema 부분집합 검증기. 살아 있는 토픽에서 표본을 떠 계약과 대조, 위반이면 exit 1 | 계약을 적어만 두면 문서다. 실제 메시지와 매분 대조할 수 있어야 계약이다 |

### 검증 (3단계 — 각각 다른 것을 증명한다)
| 단계 | 무엇을 증명 | 결과 |
|---|---|---|
| ① 검증기 자체 시험 (`--self-test`) | **검증기가 위반을 실제로 잡는가** | 4종 전부 잡음: 필수 필드 누락 / 타입 변경(문자열→숫자) / 허용값 밖 / null. 정상 메시지는 0건 |
| ② 살아 있는 토픽 대조 | 지금 흐르는 메시지가 계약대로인가 | 5개 토픽 전부 OK (표본 3건씩) |
| ③ `dbt test` 전체 | 모델 계약 | **68/68 PASS**, 모델 24개 전부 테스트 보유(열 테스트 22 + 단일 테스트 2) |

**①을 먼저 한 이유**: ②가 "전부 OK"로 나왔을 때, 그게 진짜 일치인지 검증기가 아무것도 안 보는 것인지 구분할 수 없다.
통과만 하는 검증기는 없는 것보다 나쁘다 — 안심을 주면서 아무것도 막지 않는다.

### 그 과정에서 찾은 것 두 가지 (둘 다 원래 목적 밖)
**(가) 드문 토픽은 검증기가 표본을 못 떴다.** 원장 주문 토픽은 하루 몇 건이라 끝(latest)에서 읽으면 타임아웃까지 아무것도 안 온다.
처음엔 "표본 없음 (건너뜀)"으로 넘어갔는데, 그러면 **가장 중요한 원장 토픽만 영영 검증 안 되는** 구조다.
→ 표본이 비면 `--from-beginning` 으로 한 번 더 읽도록 고쳤고, 그러자 원장도 검증됐다.

**(나) 유일성 테스트가 부하 때만 죽었다.** `assert_trades_unique_market_seq` 가 단독 실행은 12초에 통과하는데
`dbt test` 전체에서는 실패했다. 에러는 `code: 241 Memory limit (total) exceeded: 1.79 GiB > 1.57 GiB`.
- **쿼리 한도(600MB)가 아니라 서버 총 한도**였다. 하루 1,600만 행을 `(market, sequential_id)` 로 묶으면 그룹이 1,600만 개라
  해시테이블이 통째로 RAM 에 올라간다. 혼자 돌면 들어가지만, Flink 적재·다른 테스트와 겹치는 순간 총량이 넘는다.
- 고침: `max_bytes_before_external_group_by = 300MB` — 넘으면 디스크로 흘려 집계한다.
- 실측(`system.query_log`): 전체 실행 중 최대 메모리 **1.13 GiB(실패) → 556 MiB(통과)**, 소요 12.0초 → 13.9초.
- 의미: **품질 테스트가 부하 때만 실패하면 거짓 경보 생성기가 된다.** 새벽에 통과하고 장중에 실패하는 테스트는
  결국 "또 그거네" 하고 무시당한다. 알럿 체계(docs/32)를 만들어 놓고 그 안에 거짓 경보를 심을 뻔했다.

### 한계 (정직하게)
- 검증기는 **cron 에 안 걸려 있다.** 지금은 사람이 부를 때만 돈다. 매시 품질 DAG 에 붙이는 건 #9 이후.
- JSON Schema 부분집합만 지원한다(type·required·properties·items·oneOf·enum). `$ref`·`allOf`·정규식은 없다 — 필요해지면 그때.
- 표본 3건은 "계약을 지키는 메시지가 있다"는 증거이지 "전부 지킨다"의 증거가 아니다. 전수 검사는 처리량이 감당 못 한다.
- `dbt test` 를 로컬에서 돌리려면 `DBT_LOG_PATH`·`DBT_TARGET_PATH` 를 옮겨야 한다 — `logs/`·`target/` 의 파일 주인이
  Airflow 컨테이너(uid 50000)라 로컬 사용자가 덮어쓸 수 없다. 처음엔 이것 때문에 47건이 ERROR 로 나왔고,
  **데이터 실패가 아니라 파일 권한이었다**. 컨테이너와 호스트가 같은 산출물 디렉터리를 공유하는 구조의 대가다.

## 9-실행 (09-20 06:06 ~ 06:20 UTC) — 마켓 상태 SCD

### 계획이 틀렸다 — 먼저 확인한 것
이 항목의 원래 계획은 "`market/all`(is_details) + ticker 의 `market_state`·`delisting_date` 를 일 1회 스냅샷"이었다.
**둘 다 그 필드를 주지 않는다.** 코드를 쓰기 전에 실제로 호출해서 확인했다:

| 경로 | 주는 것 | market_state | delisting_date |
|---|---|---|---|
| `GET /v1/market/all?isDetails=true` | 855 마켓, `market_event`(warning·caution 5종) | 없음 | 없음 |
| `GET /v1/ticker` | 시세 26열 | 없음 | 없음 |
| `GET /v1/ticker/all?quote_currencies=KRW` | 289 마켓, 같은 26열 | 없음 | 없음 |
| **웹소켓 `ticker`** | 시세 + **`market_state`·`delisting_date`·`is_trading_suspended`·`market_warning`** | **있음** | **있음** |

같은 이름의 REST 와 웹소켓이 **다른 필드 집합**을 준다. REST 만 보고 "없다"고 했으면 이 항목은 통째로 못 했다
(docs/30 의 '부재 단정' 교훈이 네 번째로 값을 한 자리).

### 무엇을 찾았나 — 지금 이미 폐지 예정이 둘 있다
첫 스냅샷(289 마켓 전부 응답, 미응답 0):

| 상태 | 마켓 수 | 비고 |
|---|---|---|
| ACTIVE | 287 | |
| **PREDELISTING** | **2** | **KRW-RVN 폐지 2026-10-12, KRW-ICX 폐지 2026-10-19** |
| 거래 정지 | 0 | |

10월에 마켓 수가 289 → 287 로 준다. **기록이 없었다면 그날 "유실인가?"를 조사하느라 시간을 썼을 것이다.**
폐지는 정상이고 유실은 사고인데, 지금까지 그 둘을 구분할 근거가 파이프라인 안에 없었다.

### 무엇을 만들었나
| 것 | 왜 그렇게 |
|---|---|
| `clickhouse/market_state.sql` — `upbit_market_state_events` | 전이만 적재하는 이벤트 표(기존 경보 플래그 폴러와 같은 모양). `kind` 에 `snapshot`·`transition`·**`gone`**(목록에서 사라짐 = 폐지가 실제로 일어난 순간) |
| `scripts/labels/poll_market_state.py` — 10분 cron | **왜 10분인가**: 이 값을 쓰는 쪽(커버리지 판정)이 10분마다 돈다. 소비자보다 오래된 상태를 주면 판정이 옛 사실로 내려진다 |
| `scripts/lib/minws.py` — 표준 라이브러리 웹소켓 클라이언트 | 호스트에 `websockets` 가 없고 apt 설치는 sudo 가 필요했다. 쓰는 기능이 셋(접속·전송·수신)뿐이라 의존성을 늘리는 대신 직접 썼다 |
| `dim_market_state_scd` (SCD2) | "그때 그 마켓이 거래 가능했나"에 답하는 표. `is_tradable` 한 열이 하류가 보는 전부 |
| `health_check.coverage_verdict()` | 커버리지 판정에서 거래불가 마켓 제외 + 남는 알럿에 상태·폐지일 표기 |
| 주간 다이제스트 ⑥ 마켓 상태 | 폐지는 예고된 뒤 일어난다. 미리 알면 그날을 준비한다 |

**PREDELISTING 을 `is_tradable = 1` 로 둔 이유**: 폐지 예정이어도 폐지일까지는 거래된다.
여기서 빼면 그 기간의 진짜 유실을 놓친다. 빠지는 건 `DELISTED` 이거나 거래 정지된 마켓뿐이다.

### 검증 (다섯 가지, 각각 다른 것을 증명한다)
| 무엇 | 어떻게 | 결과 |
|---|---|---|
| ① 직접 쓴 웹소켓 클라이언트가 맞나 | 컨테이너의 `websockets` 라이브러리 결과와 **마켓별 전 필드 대조** | 289/289 마켓, 상태 4필드 전부 일치, 응답 필드 이름 집합도 동일 |
| ② 전이만 적재되나(멱등) | 폴러를 연속 2회 실행 | 1회차 289행(snapshot), 2회차 **0행**, 표 총계 289 그대로 |
| ③ 전이·폐지 경로가 작동하나 | `--dry-run` + 조작한 상태 파일(없는 마켓 추가, 상태 바꿈) | `transition` 1건·`gone` 1건 생성됨. **프로덕션 표 289 그대로**(가짜 행을 넣지 않고 시험) |
| ④ SCD 구간 계산이 맞나 | 5행 가상 입력에 모델과 **같은 윈도 연산**을 적용(쓰기 없음) | 반복 상태는 접히고(5행 → 4구간), 정지만 바뀐 전이도 잡히고, 구간이 빈틈·겹침 없이 이어지고, `is_current` 가 정확히 하나 |
| ⑤ 제외 분기가 맞나 | 지금 거래불가 마켓이 0개라 프로덕션에서 안 탄다 → 분기를 `coverage_verdict()` 로 빼고 **단위 테스트 6케이스** | 14/14 통과. 정지·폐지만 제외, PREDELISTING 과 **상태 모름은 제외하지 않는다** |

그 밖에: `dbt build` **102 PASS / 0 ERROR**, 커버리지 태스크 실제 실행(`checked 289, state_known 289, missing 0`),
다이제스트 ⑥ 줄 렌더링 확인(Slack 으로는 보내지 않고 쿼리·문구 조립만 — 임의로 알림을 쏘지 않는다).

### 알게 된 것 하나
`leadInFrame` 은 다음 행이 없을 때 **NULL 이 아니라 1970-01-01** 을 준다. 그래서 "마지막 구간"은
`next_at IS NULL` 이 아니라 `next_at <= valid_from` 으로 잡아야 한다. ④의 가상 입력 검증에서 드러났다 —
실제 데이터로는 아직 마켓마다 구간이 하나뿐이라 이 차이가 드러나지 않았을 것이다.

### 한계 (정직하게)
- 첫 구간의 `valid_from` 은 관측 시작(2026-09-20)이라 **'적어도 그때부터'**이지 '그때부터'가 아니다.
  `dim_market_flag_scd` 와 같은 한계이고, 이력 API 가 없어 지금은 메울 방법이 없다.
- `upbit_market_master` 는 현재 목록만 담는 ReplacingMergeTree 라 폐지되면 행이 사라진다.
  그래서 `dim_markets` 도 폐지 마켓을 잃는다. **이력은 SCD 에만 남는다** — 폐지 마켓을 조회할 때는 SCD 를 봐야 한다.
- `PREVIEW`(상장 예정) 상태는 아직 실물을 본 적이 없다. 허용값에 넣어 뒀을 뿐 검증되지 않았다.
- 10분 폴링이라 그보다 짧은 거래 정지는 못 본다.
