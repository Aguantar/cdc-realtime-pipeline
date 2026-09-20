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
