# 15. 녹화-재생 증폭 부하 실험 설계 (준확정, 2026-09-16)

- 상태: **준확정**. 관찰 분석(`docs/14`) 결과로 계단 값·코퍼스 날짜만 조정하고 구조는 유지한다. 실행은 관찰 뒤 순서(대조 DAG·dbt 계약 → 실험 준비 → 실행).
- 목적: 실시간 피드(초당 200~270건)로는 닿지 않는 파이프라인의 **포화 지점·병목 컴포넌트·선행 지표**를 실측하고, 개선 전후를 **같은 입력**으로 비교해 "현 구성의 여유는 N배"라는 SLO 문장을 만든다.
- 원칙: 합성 데이터(Faker) 미사용. 실데이터 재생만. 프로덕션 토픽·테이블·잡 무접촉(`load_test.*` 격리). 도구는 `pipeline-load-lab`(recorder/replayer/measure, dry-run 검증 완료).

## 1. 왜 재생인가 (근거)

| 근거 | 실측 |
|---|---|
| 실시간 피드로는 포화 불가 | producer 활용률 최대 13.8%(345 rows/s ÷ 2,500), Flink 백프레셔 0ms/s, 체결 e2e p95 4.7s는 배치 창(2s+3s)이 결정 |
| 실데이터 패턴이 실험의 핵심 | 마켓 편중(상위 5마켓이 호가 17%), 버스트(08-22 시간당 72.75 msg/s, 초당 232건), 같은 ms 다건 체결(BTC 60%) — 합성으로 재현 불가 |
| 실무 표준 | 프로덕션 트래픽 녹화-재생 + 결정적 비교(docs/worklog 09-16 00:35 조사). 준비생 포트폴리오는 대개 Faker·10만 건 수준 |

## 2. 3계층 설계

| 계층 | 입력 | 계단 | 목적 | 도구 |
|---|---|---|---|---|
| ① 행동 충실도 | **Upbit 자체 코퍼스**: 09-15 체결(create) 2,461,293건 + 호가 5,602,029건(6h), SHA256 고정 | 체결 50 → 100 → 200/s(각 10분), 호가 250 → 500 → 1,000/s | 우리 마켓·패턴 그대로. 개선 전후 비교의 기준선 | `replayer.py --steps` |
| ② 규모 | **Binance 공개 데이터**(`data.binance.vision`, 심볼별 daily/monthly trades zip) | 500 → 1,000 → 2,000/s(각 10분), 필요 시 5,000/s | 초당 수천 건에서 병목 위치와 선행 지표. 실데이터(다른 거래소)이므로 합성이 아님 | binance 어댑터 + `replayer.py` |
| ③ 브로커 단독 | 합성 바이트(의미 없음) | 1만~10만 msg/s | Kafka 자체 상한을 분리 → 병목이 Kafka인지 Flink·ClickHouse인지 판정 | `kafka-producer-perf-test` |
| S3 장애 | ① 100/s 지속 중 `docker stop cdc-kafka-2` 5분 | — | ISR 축소·프로듀서 acks 동작·Flink 소스 재연결·lag 회복 시간 | 수동 + `measure.py` |
| S4 개선 후 | ①과 동일 코퍼스·계단 | — | 전후 비교표 | 동일 |

## 3. Binance 코퍼스 사양 (실측 기반)

| 항목 | 내용 |
|---|---|
| 접근 | 정적 파일 HTTP 다운로드(S3/CloudFront, 서울 엣지 ICN80 응답 확인). **API 키·rate limit 없음**(문서·응답 헤더 모두), MIT |
| 파일 | `data/spot/daily/trades/{SYMBOL}/{SYMBOL}-trades-YYYY-MM-DD.zip`, 월별은 `monthly/`. `.CHECKSUM`(SHA256) 동봉 |
| 크기 실측 | BTCUSDT 일별 24MB(2026-09-14), 월별 660MB(2026-08) |
| 컬럼 | trade_id, price, qty, quote_qty, timestamp(2025-01부터 µs), isBuyerMaker, isBestMatch |
| 어댑터 매핑 | market=`BIN-{SYMBOL}`, trade_price=price, trade_volume=qty, trade_amount=quote_qty, ask_bid = isBuyerMaker ? 'ASK' : 'BID', upbit_timestamp=timestamp/1000(ms), sequential_id=trade_id. Debezium 봉투 형태(`op:"c"`, `after`, `source.ts_ms`, `ts_ms`)로 감싸 ① 경로와 같은 실험 잡이 소비 |
| 심볼 선택 | 초기: BTCUSDT·ETHUSDT·XRPUSDT·SOLUSDT·DOGEUSDT 5개 × 1일(→ 수백만 건). 규모 확장 시 상위 50심볼 × 1일 |
| 격리 | `load_test.trades` 토픽·`load_test_trades` 테이블 전용. 분석 테이블(`crypto_trades`)·마트 미혼입. 마켓 접두 `BIN-`로 실수 유입 시 식별 가능 |

## 4. 격리와 자원

- 토픽 `load_test.trades`(3파티션, RF2, retention 6h), `load_test.orderbook`(6파티션). 테이블 `load_test_trades`, `load_test_orderbook_raw`(구조 동일).
- Flink: 실험 전용 TaskManager 컨테이너(슬롯 2, process 1.5g)에 실험 잡만. 프로덕션 TM(2g, 슬롯 4/4) 미접촉. `CdcPipelineJob`은 토픽·테이블명을 환경변수로 받도록 파라미터화(실험 전 코드 변경, 배포는 실험 잡에만).
- 호스트 간섭은 관찰 cron(5분 87지표)이 프로덕션 쪽에서 계속 기록 → 단일 호스트 실험의 한계로 결과에 병기.
- 프로덕션 health_check는 실험 커넥터를 등록하지 않으므로 무영향. 실험 중 알림 폭주 방지를 위해 실험 잡의 anomaly sink는 비활성.

## 5. 측정과 판정

- `measure.py` 5초 간격: 토픽 end-offset·컨슈머 lag, Flink 소스 백프레셔/busy, 체크포인트 e2e·크기, ClickHouse 적재율·e2e p50/p95/max, 컨테이너 자원. 프로덕션 지표는 관찰 cron이 병행 기록.
- **임계점** = lag가 계단 내내 증가하거나 백프레셔 > 500ms/s가 3회 연속인 첫 계단.
- **선행 지표 후보**: 백프레셔, ClickHouse 파트 수·머지 큐, producer/실험 재생기 큐 잔량, TM heap.
- 유실·중복: 코퍼스 건수 vs `load_test_trades` count / uniqExact(market, sequential_id).
- 성공 기준(결과 문서에 반드시 포함): ① 임계점과 병목 컴포넌트, ② 선행 지표 1개 이상 → 알림 승격, ③ 개선 1건 적용 후 동일 입력 전후표, ④ "현 구성 여유 N배" SLO 문장.

## 6. 순서

관찰 분석(docs/14) → 대조 DAG·dbt 계약 → 실험 준비(코퍼스 확정·Binance 어댑터·load_test 토픽/테이블·실험 TM·잡 파라미터화) → ① → ③ → S3 → 개선 → S4 → (시간 되면) ② → 결과 문서(docs/16 예정).
