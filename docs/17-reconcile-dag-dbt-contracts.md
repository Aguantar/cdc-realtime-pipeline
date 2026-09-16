# 17. 원장 대조 DAG와 dbt 계약 — 설계 결정과 첫 실행 결과 (2026-09-16)

관찰(docs/14) 뒤 첫 구조 작업. 원칙: **현업 관행이거나, 아니면 "왜"를 한 문장으로 답할 수 있는 선택만 한다.**

## 1. 결정표

| 결정 | 선택 | 왜 (한 문장) | 대안과 기각 이유 |
|---|---|---|---|
| 역할 분리 | Airflow = 외부 참조값 적재·순서·재시도·알림 / dbt = 비율 계산·판정 테스트 | 비즈니스 SQL 이 DAG 파일 안에 있으면 버전관리·리뷰·재현이 안 된다 (EL 과 T 의 분리) | DAG 안 inline SQL(daily_pipeline 방식): 이전 코드라 유지하되 새 작업엔 안 씀 |
| 참조값 보관 | `upbit_hourly_candles` 테이블(ReplacingMergeTree, TTL 90일) | 결과만 남기면 재계산·감사가 불가. 재적재 시 최신본 대체라 백필이 멱등 | CSV 파일(관찰 기간 cron 방식): 조회·조인 불가 |
| 판정 위치 | dbt 싱귤러 테스트 2개(유실 <99%, 커버리지 ch_n=0) | 테스트는 데이터 정의 옆에 있어야 함. `dbt build` 한 번에 모델+테스트 | Airflow PythonOperator 판정: 임계값이 코드 여기저기 흩어짐 |
| 유실 임계 99% | 셀(마켓×시간) 단위 | docs/13 수정 후 6일 연속 100.0%, 거래소 봉 경계 오차가 1% 미만 | 95%: 관찰 실측보다 느슨해 이번 20:47 손실(TIA 50.6%)은 잡지만 SNT 89% 는 놓칠 뻔 |
| 커버리지 테스트 분리 | 거래소 거래 있음 & 우리 0건 | 구독 누락은 "비율"이 아니라 "부재"로 나타남. 마켓 목록의 기준을 ClickHouse 가 아니라 **거래소**로 둔 것이 핵심 | 관찰 기간 cron 은 우리 목록 기준이라 BFC 를 6일간 못 봤다 |
| 대상일 | `{{ ds }}` = 전날(UTC), 06:35 UTC 실행 | UTC 하루가 닫힌 뒤 6.5h 여유, 관찰 cron 과 같은 시각이라 연속성 | KST 하루: 운영 대조는 UTC, 분석은 KST 로 고정(아래) |
| REST 직렬화 | Airflow pool `upbit_rest` 1 슬롯 | 초당 10회/IP 한도. DAG 간 동시 호출로 429 가 났던 09-10 사고 재발 방지 | 태스크별 sleep 만: DAG 가 둘 이상 되면 못 지킴 |
| Slack | 실패 시에만, 최악 셀 10개 포함 | 매일 "정상" 은 alert fatigue | — |
| "하루" 정의 | 분석·리포트 = KST 체결시각, 운영 대조 = UTC | 업비트는 KST 24시간 시장, 리포트는 01:00 KST 에 "전날" 을 뜻함. daily_pipeline 은 종전 `toDate(source_ts)`(UTC 적재시각)로 걸러 9시간 어긋난 하루를 KST 로 보고했음 → 체결시각 KST 범위로 교체 | — |
| 유일성 계약 | `(market, sequential_id)` 전날 KST 창만 | 1억 행 전체 유일성은 1.75GB ClickHouse 에서 매일 불가. 과거는 07·13 감사 완료. 새로 들어온 창만 검사 = 증분 테스트 관행 | dbt_utils 패키지: 테스트 2개에 패키지 의존을 추가할 이득이 없어 단일 SQL 로 |
| 소스 freshness | `dbt source freshness` 를 daily_pipeline 첫 태스크로 | 소스가 멈춘 채 mart 를 재계산하면 "정상 완료"로 위장된다. health_check(10분 즉시 알림)와 역할 분리 | — |
| 컨테이너 테스트 | DAG 구조 테스트를 컨테이너 안에서 pytest | 이미지에 pytest 없어 실행 안 되고 있었고, 기존 테스트 2개는 실제 DAG 와 달라 실패 상태였음 → 수정. Dockerfile 에 pytest 추가(다음 빌드) | — |
| cron 대조 | 비활성화 | DAG 와 같은 06:35 에 REST 충돌 | — |

## 2. 구성

```
reconcile_trades (06:35 UTC, catchup=False, backfill 가능)
  fetch_hourly_candles  [pool upbit_rest]  거래소 KRW 마켓 목록 → 시간봉 24개/마켓 → upbit_hourly_candles
  → dbt_build_reconcile  dbt build --select +int_reconcile_hourly --vars reconcile_date=ds
       모델 int_reconcile_hourly: 마켓×시간 ch_vol / candle_vol (체결시각 기준, FINAL)
       테스트 assert_reconcile_no_loss(ratio<99), assert_reconcile_coverage(ch_n=0), not_null
  → summarize [all_done]  가중 비율·셀·최악 10개 → 실패면 Slack + 태스크 실패
```
dbt 추가: `reference` 소스(시간봉·일봉·경보 이력), `crypto_trades` freshness(15분 warn / 60분 error), `assert_trades_unique_market_seq`.
daily_pipeline 변경: `dbt_source_freshness` 선행, KST 체결시각 하루 경계, `schedule` 파라미터.

## 3. 첫 실행 결과 (대상일 2026-09-15, 09-09~14 백필 포함)

| 일(UTC) | 가중 비율 | 셀 | <99% | 우리 0건 | 0건 마켓 |
|---|---|---|---|---|---|
| 09-10 | 99.97 | 6,765 | 22 | 20 | 1 |
| 09-11 | 99.99 | 6,779 | 24 | 24 | 1 |
| 09-12 | 99.99 | 6,717 | 24 | 24 | 1 |
| 09-13 | 99.99 | 6,814 | 24 | 24 | 1 |
| 09-14 | 100.00 | 6,833 | 24 | 24 | 1 |
| 09-15 | 99.72 | 6,852 | 32 | 24 | 1 |

**발견 1 — 구독 누락 (커버리지 테스트가 잡음).** KRW-BFC 는 09-10 상장. producer·수집기는 09-09 기동 시 마켓 목록을 한 번만 받아 이후 상장 마켓을 구독하지 않는다. 체결 0건, 호가 0건, 6일째. 관찰 기간 cron 대조는 마켓 목록을 ClickHouse 에서 가져와 **구조적으로 볼 수 없었다**(6일 연속 "100.0%" 는 이 마켓을 제외한 값). docs/14 튜닝 5번(마켓 목록 갱신)이 실제 손실로 확인됨. 업비트 REST 체결 이력은 7일이라 BFC 백필 가능 기한은 09-17 까지.

**발견 2 — 재연결 구멍 (유실 테스트가 잡음).** 09-15 20:47:00 UTC producer WebSocket 끊김 → 20:47:05 재연결. 그 5초 사이 체결이 빠졌다. TIA 20시 셀 50.6%: REST 틱 대조로 20:47:02 체결 2건(2,280 + 20 TIA) 누락 확인. 같은 시간대 8마켓 셀이 89~97%. 일 단위로는 99.27% 이상이라 종전 가중 비율만 보면 넘어갔을 크기. 관찰 기간 producer WARNING 1건(docs/14 미조사 항목)의 정체가 이것.

## 4. 후속 (사용자 결정 필요 — 파이프라인 변경)
1. BFC 백필(REST 7일 창, MySQL 경유, 09-10~) — 09-17 전에 해야 함.
2. 20:46:55~20:47:10 창 전 마켓 백필(같은 방법).
3. producer·수집기: 마켓 목록 주기 갱신(5분) + 재연결 뒤 REST 로 끊긴 구간 메우기(gap-fill). 둘 다 코드 변경·재기동.
4. 3일 연속 DAG 성공 확인 뒤 cron 스크립트 파일 정리.
