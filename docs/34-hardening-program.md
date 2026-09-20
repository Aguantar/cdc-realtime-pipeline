# 34. 보강 프로그램 — docs/33 의 약점 전부 (2026-09-20 시작)

> 사용자: "말한 모든 내용을 다 보강하자. 철저하게, '왜?'에 답이 되게. Float 도 DE 의 일 아닌가." → 맞다. 저장 층의 숫자 타입은 하류에 주는 계약이고, 정밀도는 원천(DECIMAL·문자열)에 있었는데 Flink 에서 double 로 버린 것이라 DE 책임.
> 원칙: 항목마다 **왜 → 무엇을 → 어떻게 검증** 을 먼저 적고, 실행 뒤 결과를 §N-실행 에 붙인다. 순서는 위험(보안·정확성) → 계약(시간·차원·타입) → 정리.
> 진행 상황(09-20 05:30 UTC): #1~#6 완료·커밋·푸시(§1-실행~§6-실행). 남은 것 #7~#10.

| # | 항목 | 왜 | 무엇을 | 검증 | 상태 |
|---|---|---|---|---|---|
| 1 | 보안 | 3306·8123·9092 등이 LAN 에 무인증 노출, producer 가 root | ① `sudo scripts/ops/lan-firewall.sh`(사용자) ② Kafka/ZK 를 뺀 서비스 포트를 127.0.0.1 바인딩(재생성 1회씩) ③ producer 전용 MySQL 사용자(INSERT/SELECT crypto_trades) | LAN 의 다른 기기에서 `nc -zv <host> 3306` 실패, producer 적재 지속, 재시작 창 대조 | **완료(부분)** — 방화벽은 사용자 sudo 대기 |
| 2 | RMT 읽기 FINAL | ReplacingMergeTree 는 "결국" 중복 제거. 읽는 쪽이 보장해야 마트가 재시작 뒤 중복을 안 센다 | stg_trades 에 FINAL, 하류 6모델은 stg 경유(dim_markets·int_reconcile_hourly·int_alert_transitions_recomputed·int_volume_surge_daily·dq_ingest_daily 점검) | dbt build 통과, 중복 주입 뒤 마트 count 불변 실험 | **완료** 04:08 |
| 3 | 하루 규약 | 마트=KST, dq=UTC 인데 열 이름이 둘 다 day | `day_kst`/`day_utc` 로 이름 통일, docs 규약 한 줄, Grafana·DAG 쿼리 동시 수정 | dbt build + 대시보드 12패널 조회 + DAG 테스트 | **완료** 04:15 |
| 4 | 차원·사이드 | 코인 키가 거래소마다 다르고 문자열 치환으로 조인, 사이드 의미 반대 | `dim_coins`(coin_id·upbit_market·binance_symbol·base·quote·유효기간), `dim_venues`, 마트 `taker_side`. 환율은 Upbit KRW-USDT 마켓(우리 데이터) → `sig_kimchi_premium` | 조인 유일성 테스트, 김프 값이 공개 지표와 같은 부호·자릿수 | **완료** 04:20 |
| 5 | Decimal | 금액·수량 Float64 는 회계·대조 등호에 못 쓴다. 원천은 정밀 | Flink 파서 BigDecimal → `setBigDecimal`, ClickHouse crypto_trades/binance_trades price·volume·amount Decimal(20,8)/(24,8) 로 무정지 재생성(EXCHANGE 런북), 마트 파생 타입 확인. 호가 배열은 Float64 유지(파생 지표) — 이유 명시 | 재생성 전후 sum(amount) 등호(Decimal 끼리), 프루닝·적재 지속 | **완료** 05:19 |
| 6 | 재처리 런북 | 보존은 있는데 절차가 없다 | `scripts/ops/reprocess-day.sh`: 원장(MySQL, 7일) → ClickHouse `mysql()` 함수로 하루 파티션 재생성, Binance 는 Kafka(3일) 재소비 잡, 호가는 Parquet(120일) | 실제 하루를 다시 만들어 대조 100% | **완료** 05:30 |
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
