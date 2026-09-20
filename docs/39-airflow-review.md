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
| ~~품질 게이트~~ | **정정(12:20): `quality_gate` 는 막지 않는다** — `ti.log.warning` 만 찍고 dict 를 반환한다. 이름만 게이트였다 | 아래 §2 ⑧ 로 옮김 |
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

### ⑧ `quality_gate` 가 이름만 게이트다 (12:20 자가 정정)
이 문서 첫 판에서 "quality_gate 가 하류를 막는다"고 적었는데 **코드를 다시 읽으니 틀렸다.**
```python
if failed:
    ti.log.warning("Failed coins: %s", gate_result["failed_coins"])
return gate_result          # ← 예외를 던지지 않는다
```
실패 코인이 있어도 `generate_report >> slack_daily_report` 가 그대로 돈다.
**틀린 값을 Slack 으로 보내지 않는다는 보장이 없다.** `AirflowFailException` 이나 `ShortCircuitOperator` 가 있어야 게이트다.

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

---

## 4. 일반적인 "Airflow 를 쓰는 이유" 5가지와 대조 (2026-09-20 12:20 UTC)

> 사용자가 정리한 기준(코드화 / 의존성·조건부 / 복구·멱등성 / 생태계·확장성 / 모니터링)으로 하나씩 실제 코드와 대조했다.
> **되어 있는 것 · 부족한 것 · 우리 규모엔 불필요한 것** 셋으로 나눈다. 세 번째를 구분하지 않으면 남의 규모를 흉내 내게 된다.

### ① 파이프라인의 코드화 — **대체로 됨**
| 항목 | 상태 | 근거 |
|---|---|---|
| Git 버전 관리 | ✅ | DAG 10개가 저장소에, `airflow/tests/test_dags.py` 15개, **CI 3종**(Flink 단위 · DAG 구조 · dbt parse) |
| **동적 파이프라인 생성** | ✅ **이미 쓰고 있다** | `daily_pipeline` 이 `PythonOperator.partial(...).expand(...)` 로 **코인 수만큼 검증 태스크를 동적 생성**(Airflow 2 Dynamic Task Mapping) |
| 재사용성 | 🟡 | 커스텀 `ClickHouseOperator`·`FlinkHealthOperator`, `ClickHouseHook`, 공용 콜백은 있다. **TaskGroup 은 미사용** — DAG 당 태스크가 3~8개라 아직 묶을 것이 없다. 태스크가 늘면 그때 |

### ② 복잡한 의존성 · 조건부 제어 — **가장 약한 축**
| 항목 | 상태 | 근거 |
|---|---|---|
| 복잡한 DAG | 🟡 | `daily_pipeline` 에 갈래 합류는 있다(`dbt_test` → 검증·중복체크 두 갈래 → gate) |
| **분기(Branching)** | ❌ | `BranchPythonOperator`·`ShortCircuitOperator` 미사용 |
| **게이트가 안 막는다** | ❌ | 위 §2 ⑧ — 이름만 게이트 |
| **센서(Sensors)** | ❌ | 하나도 없다. `Dataset` 은 선언만 하고 소비자가 없다(§2 ②) |
| TriggerRule | 🟡 | `reconcile_trades` 한 곳(`ALL_DONE`)만 |

**우리 맥락에서 센서가 필요한 자리**: 대조 DAG 이 "거래소 캔들이 그 시각에 확정됐는지"를 **시간 오프셋(06:35)으로 추측**한다.
`PythonSensor` 로 "그 시간의 캔들이 실제로 있는가"를 확인하면 추측이 사실로 바뀐다. S3 같은 외부 파일은 우리에게 없다.

### ③ 복구 · 멱등성 — **재시도는 되고 백필은 안 된다**
| 항목 | 상태 | 근거 |
|---|---|---|
| 재시도 | ✅ | 10/10 DAG, `retries` 1~3 |
| 태스크 단위 재실행 | 🟡 | 구조상 가능. 다만 **`dbt_run` 이 단일 태스크**라 dbt 안에서는 24 모델 전부 재실행(§2 ④) |
| **백필** | ❌ | `catchup=False` + **dbt 가 `now()` 기준**이라 과거 날짜를 돌려도 오늘 창을 만든다(§2 ①, docs/40 ⑩) |
| 멱등성 | 🟡 | 증분 3개는 `delete+insert`(unique_key=day_utc)로 멱등. 대조·백업은 `{{ ds }}` 기반이라 멱등. **dbt 전체는 아직 아니다** |

### ④ 생태계 · 확장성 — **Provider 0개, 그러나 확장은 불필요**
| 항목 | 상태 | 판단 |
|---|---|---|
| Provider | ❌ **하나도 설치 안 됨** | Slack 은 `requests` 직접 호출, ClickHouse 는 자체 Hook. 자격증명은 **Airflow Connection·Variable** 로 올바르게 관리 중(env 하드코딩 아님). Slack Provider 로 바꾸면 재시도·연결 관리를 공짜로 얻는다 — 작은 개선 |
| 분산 확장 | ⬜ **불필요** | `LocalExecutor`, 1호스트 16GB, 동시 태스크 8. Celery·Kubernetes Executor 는 워커 노드가 여럿일 때의 답이다. **여기서 쓰면 관리 비용만 늘고 얻는 게 없다** — 안 쓰는 것이 맞는 판단이고, 면접에서도 그렇게 말하면 된다 |

### ⑤ 모니터링 · 디버깅 UI — **됨**
| 항목 | 상태 |
|---|---|
| UI | ✅ `airflow.calmee.store`(Caddy basic_auth + Airflow 로그인), Grid·Gantt·로그 기본 제공 |
| 로그 | ✅ 호스트 볼륨에 보존 — **다만 3.4 GB, 정리 정책 없음**(아래) |

### 4-1. 이 대조로 새로 나온 항목
| # | 무엇 | 왜 |
|---|---|---|
| ⑧ | `quality_gate` 를 실제 게이트로(`AirflowFailException` 또는 `ShortCircuitOperator`) | 지금은 틀린 값도 Slack 으로 나간다 |
| ~~⑨~~ | ~~대조 DAG 에 캔들 존재 센서~~ → **철회(12:35, 아래 §4-2)** | 근거를 대 보니 막을 위험이 없었다 |
| ⑩ | Airflow 로그 보존 정책(`log_retention_days` 또는 정리 DAG) | 3.4 GB, 디스크는 유한하다 |
| ⑪ | Slack Provider 도입 검토 | 재시도·연결 관리를 표준으로 |

**불필요로 확정**: Celery/Kubernetes Executor, TaskGroup(아직), 외부 스토리지 센서(S3 등 우리에게 없음).

### 4-2. ⑨ 센서 — 철회 (12:35 자가 재검토)
> 사용자: "파이프라인 헬스체크 관점에서 정말 필요한가? 객관적으로 재검토해 달라."

**필요 없다.** 데이터로 확인한 근거 넷:

| # | 확인한 것 | 결과 |
|---|---|---|
| 1 | 대조 DAG 이 캔들을 **어디서 얻나** | 남의 적재를 기다리는 것이 아니라 **스스로 REST 로 받아온다**(`_fetch_hourly_candles`). 한 마켓이라도 실패하면 `RuntimeError` — **이미 하드 게이트가 있다** |
| 2 | 미확정 캔들이 섞일 수 있나 | 대상일은 실행 6.5시간 전에 닫힌다. 표에 **미래 시각 캔들 0건** — 부분 캔들이 들어온 적이 없다 |
| 3 | 재적재 중복이 분모를 부풀리나 | `upbit_hourly_candles` 는 **ReplacingMergeTree(market, hour_utc)** 이고 대조 모델이 **FINAL 로 읽는다.** 09-15 는 실제로 두 번 받아 6,852건 중복인데 결과는 정상 |
| 4 | 실적 | 대조 11일 전부 가중 비율 100%, 최소 셀 100% |

**센서는 "남이 만드는 데이터를 기다릴 때" 쓰는 도구다.** 우리는 그 데이터의 생산자를 직접 소유한다 —
기다릴 대상이 없으므로 센서를 넣으면 기다리는 시늉만 하는 코드가 된다(워커 슬롯만 점유).

**부수 확인**: 캔들이 마켓당 24개가 아니라 23.5~23.9개인 것은 **정상**이다 —
체결이 없는 시간에는 업비트가 캔들을 만들지 않는다. "24개여야 한다"는 테스트를 넣었으면
매일 거짓 경보가 났을 것이다(docs/34 #3 의 `no_long_gaps` 와 같은 함정).

**센서가 의미 있어지는 조건**(지금은 아님): 캔들 적재를 별도 DAG·외부 적재로 분리하거나,
외부 팀이 올려주는 파일을 받게 되면 그때 필요하다.
