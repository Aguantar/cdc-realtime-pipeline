# 15. 녹화-재생 증폭 부하 실험 설계 (준확정, 2026-09-16)

- 상태: **준비 완료 (2026-09-17, §7)**. 관찰 분석(`docs/14`) 결과로 계단 값·코퍼스 날짜만 조정하고 구조는 유지한다. 실행은 관찰 뒤 순서(대조 DAG·dbt 계약 → 실험 준비 → 실행).
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

## 7. 준비 결과 (2026-09-17 02:10 ~ 02:30 UTC, 실측)

| 항목 | 결과 | 검증 |
|---|---|---|
| 잡 파라미터화 | `CdcPipelineJob`·`OrderbookJob` 이 `CDC_TOPIC`/`ORDERBOOK_TOPIC`, `*_GROUP_ID`, `CLICKHOUSE_TABLE_PREFIX`, `MARKET_ALERTS_ENABLED`, `JOB_NAME` env 를 읽는다. 기본값 = 프로덕션과 동일 | 프로덕션 잡은 재배포하지 않았다(기본값 불변, 잡 그래프는 제출 시 고정). 실험 잡만 새 jar 로 제출 |
| 실험 TM | compose 프로필 `lab` 의 `flink-taskmanager-lab`(process 1.5g, 슬롯 2, 제한 1.75G) | JM overview TM 2 / 슬롯 6. 실험 잡은 프로덕션 TM 슬롯이 4/4 라 lab TM 에만 배치됨(free 0/0) |
| 격리 | 토픽 `load_test.trades`(3p, RF2, 6h)·`load_test.orderbook`(6p), 테이블 `load_test_*` 4개(`AS` 원본, TTL 1일) | 스모크 3,000건 → `load_test_crypto_trades` 3,000 / uniq 3,000, 프로덕션 `crypto_trades` 오염 0, `market_alerts` 0 (탐지기 비활성 → 잡 정점 2개뿐) |
| 재생기 시각 이동 수정 | rate/steps 모드에서 원본 간격 기준 상대 이동을 쓰면 원본보다 빨리 보낼 때 시각이 미래로 가 e2e 가 음수(p50 −14.4s 실측) → "메시지 시각 = 보내는 순간"으로 | 재스모크 e2e p50 2.99s / p95 4.75s / min 0.36s — 프로덕션 평시(4.7s)와 같은 자리 |
| Binance 코퍼스 | 5심볼 × 2026-09-15 일별 zip, `.CHECKSUM` SHA256 전부 일치, `BIN-{SYMBOL}` Debezium 봉투로 변환(`binance_adapter.py`) | 행 수·파싱 스모크는 worklog |
| 프로덕션 가드 | `prod_guard.py`: 프로덕션 체결 e2e p95(60초 창) > 30초 3회 연속이면 재생기 kill | 스모크 2회 폴링 4.68/4.93s, bad 0 |

**단일 호스트 한계에 대한 답** (사용자 질문 "지연 p95 오르는 거 해결책 없나"):
같은 사양의 두 번째 호스트 없이는 완전한 해결책이 없다. 실무는 동일 사양 스테이징에서 부하 실험을 한다. Oracle A1 은 ARM·다른 사양이라 "현 구성 여유 N배" 문장의 근거가 될 수 없다. 대신 세 가지로 줄인다.
1. **창**: 규모 계단(②)은 KST 02~06(UTC 17~21, 체결 11~16 rows/s, docs/14 §2)에 돌린다. ①(50~200/s)은 프로덕션의 2~8배라 시간 제약 없이 돌리되 결과에 프로덕션 유입률을 병기.
2. **가드**: 위 `prod_guard.py`. 임계 30초의 근거는 60초 늦은 이벤트 가드(docs/20) — 프로덕션 행이 탐지에서 빠지기 전에 실험을 멈춘다.
3. **기록**: 실험 창을 결과 문서에 남기고 그날 `dq_ingest_daily` p95 옆에 병기. 프로덕션 p95 상승 자체도 "같은 호스트에서 얼마까지 같이 살 수 있나"라는 결과다.
cpuset 고정은 4코어 N100 에서 실험 상한을 먼저 깎아 의미가 없다(실험 2코어 = 프로덕션 2코어와 경쟁이 아니라 실험 자체가 포화). 메모리는 lab TM 1.75G 제한으로 프로덕션 OOM 을 막는다(가용 6GB).
