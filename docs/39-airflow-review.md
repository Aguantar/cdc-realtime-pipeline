# 39. Airflow 구성 점검 — 실무(DE) 관점 (2026-09-20 11:45 UTC)

> 사용자: "실무 관점(데이터 엔지니어링)에서 airflow 가 알맞게 구성되어 있는지 확인해줘. 부족하다면 수정할 수 있으니 어떻게 할지도 계획을."

DAG 10개를 설정·의존성·날짜 파라미터·재시도·모니터링 축으로 실제 코드에서 확인했다.

## 1. 잘 돼 있는 것 (그대로 둔다)

| 항목 | 상태 | 왜 중요한가 |
|---|---|---|
| 실패 알림 | **10/10 DAG 에 `on_failure_callback`** | 조용한 실패가 없다 |
| 동시 실행 | **10/10 `max_active_runs=1`**, `catchup=False` | 느려진 DAG 이 겹쳐 돌며 서로를 밀어내지 않는다 |
| 날짜 파라미터 | `reconcile_trades`·`reconcile_binance`·`backup_daily` 가 `{{ ds }}`·`data_interval` 사용 | **과거 날짜를 다시 돌릴 수 있다.** 대조·백업은 재실행이 의미 있는 작업 |
| 외부 API 보호 | `upbit_rest` 풀로 동시 호출 제한 | 거래소 rate limit 을 우리가 스스로 만들지 않는다 |
| 소스 신선도 | `daily_pipeline` 첫 단계가 `dbt source freshness` | **소스가 멈춘 채 마트를 다시 만들어 '정상 완료'로 위장하는 것**을 막는다 |
| 품질 게이트 | `quality_gate` 가 하류(리포트)를 막는다 | 틀린 값을 Slack 으로 보내지 않는다 |
| 테스트 | `airflow/tests/test_dags.py` 15개 | 임포트·스케줄·콜백·분기 로직 |

## 2. 부족한 것 (우선순위 순)

### ① dbt 가 날짜 파라미터를 안 받는다 — **백필이 불가능하다** (가장 큼)
증분 모델이 전부 `now()` 기준으로 창을 자른다.
```sql
{% if is_incremental() %} WHERE recv_ts >= toStartOfDay(now() - INTERVAL 1 DAY)
```
`daily_pipeline` 도 `dbt run` 을 변수 없이 부른다. 그래서 **Airflow 에서 과거 날짜를 재실행해도 오늘 창을 다시 만든다.**
대조·백업은 `{{ ds }}` 를 쓰는데 dbt 만 안 쓴다 — 한 파이프라인 안에서 재실행 가능 여부가 갈린다.

> 이것이 왜 면접에서 걸리나: "9월 12일 마트가 틀렸다. 그날만 다시 만들어라"에 지금은 답이 없다.

### ② Dataset 이 선언만 되고 소비자가 없다
`DBT_COMPLETED = Dataset("clickhouse://cdc_pipeline/dbt_models")` 를 `dbt_test` 가 **생산**하는데 **아무도 구독하지 않는다.**
`quality_alerts` 는 매시 :50 이라는 **시간 추측**으로 돈다 — dbt 가 늦으면 옛 데이터로 판정한다.

### ③ 호스트 cron 9개가 Airflow 밖에 있고, 죽어도 모른다
`collect_metrics.sh` · `poll_market_state.py` · `poll_exchange_notices.py` · `validate-topic-schemas.py` ·
`poll_market_events.py` · `fetch_market_event_records.py` · `sync-annotations.sh` · `daily_digest.py` · `n.sh`.

Airflow 밖에 둔 이유는 타당하다(Kafka CLI·docker exec 가 필요한데 DAG 은 "네트워크 API 기반, 소켓 불필요" 원칙을 지킨다).
문제는 **정지를 알아채는 장치가 계약 검증기 하나뿐**이라는 것. 나머지 8개는 죽어도 조용하다.

### ④ `dbt_run` 이 단일 태스크
모델 24개가 한 덩어리다. 어느 모델이 느린지·실패했는지 태스크 단위로 안 보이고, 재시도가 **전부 재실행**이다.

### ⑤ SLA 가 `daily_pipeline` 한 곳뿐
대조·백업이 늦어도 아무도 모른다. 실패는 알리지만 **지연은 안 알린다.**

### ⑥ 재시도 백오프가 거의 없다
`reconcile_trades` 만 `exponential_backoff`. 외부 API 가 흔들릴 때 고정 간격 재시도는 같은 실패를 반복한다.

### ⑦ `depends_on_past` 없음
증분 모델은 전날 결과 위에 쌓는데, 전날이 실패해도 오늘이 돈다 → 구멍이 조용히 남는다.

## 3. 계획 — 3단계

### 1단계 (작고 즉시 효과, 반나절)
| # | 무엇 | 검증 |
|---|---|---|
| ② | `quality_alerts` 를 `schedule=[DBT_COMPLETED]` 소비자로 (매시 판정은 유지하되 dbt 완료 시에도 트리거) | dbt_test 성공 직후 quality_alerts 실행 이력 |
| ③ | cron 산출물 **신선도 판정**을 `quality_alerts` 규칙으로 추가(표별 마지막 기록 vs 허용 지연) | 규칙을 일부러 어겨 알림 발사 확인(단위 테스트) |
| ⑤ | 핵심 DAG 에 SLA(`reconcile_trades` 2h, `backup_daily` 2h, `quality_alerts` 20m) | `sla_miss_callback` 동작 |
| ⑥ | 외부 API 쓰는 DAG 에 `retry_exponential_backoff=True` + `max_retry_delay` | 설정 반영 확인 |

### 2단계 (백필 가능하게, 하루)
| # | 무엇 | 검증 |
|---|---|---|
| ① | `dbt run --vars '{"run_date": "{{ ds }}"}'`, 증분 모델의 `now()` → `{{ var('run_date') }}` 기준으로. 기본값은 오늘이라 평소 동작은 그대로 | **과거 하루만 재계산**해 값이 원본과 일치하는지(#6 재처리 검증과 같은 방식: 행 수가 아니라 합계로) |
| ⑦ | 증분 DAG 에 `depends_on_past=True` | 전날 실패 시 오늘이 멈추는지 |

### 3단계 (관측성, 반나절)
| # | 무엇 | 검증 |
|---|---|---|
| ④ | `dbt_run` 을 레이어 3태스크로 분할(staging → intermediate+dimensions → marts+quality). `--select` 로 자른다 | 태스크별 소요 시간이 Airflow UI 에 나오는지. cosmos 같은 도구는 과하다 — 모델 24개에 태스크 3개면 충분 |

**순서 근거**: 1단계는 "지금 조용한 실패를 드러내는" 것이라 먼저. 2단계는 모델을 고쳐야 해서 검증 비용이 크고,
3단계는 있으면 좋지만 없어도 파이프라인이 틀리지 않는다.
