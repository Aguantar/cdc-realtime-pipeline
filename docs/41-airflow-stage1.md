# 41. Airflow 1단계 실행 + 단계별 사건 이력 (2026-09-20 13:25 ~ 13:55 UTC)

docs/39 의 1단계 항목과, 사용자가 던진 질문에서 나온 새 항목 하나를 함께 했다.

> 사용자: "각 파이프라인 순으로 오류가 났는지랑 왜 났는지까지 Airflow 에 들어가?"
> → **반만 들어간다.** 그 반쪽을 메우는 것이 §2 다.

## 1. 1단계 항목

### ⑧ `quality_gate` 를 진짜 게이트로
전에는 실패 코인을 `log.warning` 으로 찍고 **dict 를 반환**했다. 하류 `generate_report >> slack_daily_report` 가
그대로 돌았으므로 **틀린 값이 Slack 으로 나갔다.** 이름만 게이트였다.

```python
if gate_result["total"] > 0 and gate_result["pass_rate"] < QUALITY_GATE_MIN_PASS_RATE:
    raise AirflowFailException(...)   # 리포트를 보내지 않는다
if dup_rows > 0:
    raise AirflowFailException(...)
```

**임계를 '0건 실패'가 아니라 90% 로 둔 이유**: 얇은 마켓 한두 개가 순간적으로 조건을 못 맞추는 일은 늘 있다.
그때마다 리포트를 막으면 **사람이 게이트를 꺼 버린다.** 90% 는 '몇 개가 이상'과 '전반이 깨짐'을 가르는 선이다.
중복 적재는 0건이 아니면 무조건 막는다 — 그건 정도의 문제가 아니라 사고다.

### ② Dataset — 하려다 **막혀서 다른 길로**
`quality_alerts` 를 `schedule=[Dataset(...)]` 로 바꿨더니 **매시 실행이 사라졌다.**
`DatasetOrTimeSchedule`(cron + Dataset 동시)은 **Airflow 2.9+ 이고 우리는 2.8.1** 이다.
매시 실행을 잃으면 dbt 가 안 도는 시간대의 **cron 신선도·계약 검증**을 놓친다.

→ 매시는 그대로 두고, `daily_pipeline` 이 `dbt_test` 직후 `TriggerDagRunOperator` 로 **명시적으로** 부른다.
추측(":50 쯤이면 끝났겠지")이 사실(끝났으니 부른다)로 바뀌었고, 2.9 로 올리면 한 줄로 합칠 수 있다.

### ③ 호스트 cron 신선도 판정
Airflow 밖 cron 9개 중 **정지를 알아채는 장치가 계약 검증기 하나뿐**이었다.
cron 은 자기 산출물을 표에 남기므로, **그 표의 마지막 기록이 오래됐으면 cron 이 멈춘 것**이다.

| 대상 | 주기 | 허용 지연 | 왜 그 값인가 |
|---|---|---|---|
| `ops_metrics_5m` | 5분 | 30분 | 주기의 6배. 한 번 걸러도 안 울린다 |
| `upbit_market_state_events` | 10분 | **1500분** | **전이가 있을 때만 쓰는 표**라 조용한 것이 정상이다. 실측 312분 무기록 |
| `exchange_notices` | 매시 | 180분 | 주기의 3배 |
| `upbit_market_events` | 1분 | 30분 | 플래그 전이도 드물다 |

**허용 지연을 주기로만 정하면 안 된다** — 쓰기가 드문 표는 조용한 것이 정상이기 때문이다.
이걸 모르고 `market_state` 에 30분을 걸었으면 매일 거짓 경보가 났다.

### ⑤ SLA · ⑥ 지수 백오프
`reconcile_trades`(2h) · `backup_daily`(2h) · `quality_alerts`(20m) · `market_alerts_notify`(5m) 에 SLA.
**실패는 알리는데 지연은 안 알리고 있었다.**
외부 API 를 쓰는 4개 DAG 에 `retry_exponential_backoff` + `max_retry_delay=10m` — 고정 간격 재시도는 같은 실패를 반복한다.

### 작업 중 내가 만든 실수 — 일괄 편집이 코드를 깨뜨렸다
SLA 를 네 DAG 에 한 번에 넣으려고 `task_id="…"` 뒤에 문자열을 끼워 넣었는데,
`quality_alerts`·`market_alerts_notify` 는 **한 줄짜리 연산자**였다.

```python
PythonOperator(task_id="judge_quality",
    sla=timedelta(minutes=20),   # …다, python_callable=_judge)   ← 주석이 나머지 인자를 삼켰다
```

`SyntaxError: '(' was never closed` 로 DAG 2개가 죽었고 테스트 4개가 실패했다.
**같은 패턴이 두 가지 형태로 존재하는 파일을 정규식으로 일괄 편집하면 이렇게 된다.**
두 파일은 손으로 여러 줄 형태로 고쳤고, 편집 직후 **전 DAG 문법 검사 + 테스트**를 돌려 잡았다.
(교훈: 일괄 편집 뒤에는 반드시 전수 문법 검사를 한다 — docs/30 의 '검증 전 커밋' 과 같은 부류)

### ⑩ 로그 보존
| | 값 |
|---|---|
| 전 | **3.4 GB**, 14일 초과 파일 157,210개, 보존 정책 없음 |
| 조치 | `log_retention` DAG(주 1회 일요일 03:30 UTC, 30일 보존) |
| 후 | **688 MB** (70,888 파일 삭제, 1,363 MB 확보) |

**호스트 cron 이 아니라 DAG 으로 둔 이유**: 로그는 Airflow 자신의 산출물이고, cron 으로 두면
"Airflow 밖에서 도는 것"이 하나 더 늘어 ③ 문제를 키운다. 실패하면 알림이 오고 재시도도 된다.

**첫 실행에서 `errors: 58,699`** 가 나왔는데 정작 30일 초과 파일은 **0개** 남았다(삭제는 전부 성공).
숫자만 있고 이유가 없으면 다음 사람이 무시하거나 겁먹는다 → **오류를 종류별로 세도록** 고쳤다.
두 번째 실행은 `errors: 0, error_kinds: {}` — 첫 실행의 오류는 일시적이었고, 재발하면 종류가 남는다.

### ⑪ Slack Provider — **도입하지 않기로**
지금은 `requests` 직접 호출이고 웹훅은 **Airflow Variable** 에 있다(하드코딩 아님).
Provider 로 바꾸면 Connection 관리와 재시도를 표준으로 얻지만, **우리가 지금 겪는 문제가 아니다.**
Airflow 이미지에 의존성을 하나 더 넣는 비용이 이득보다 크다. 2.9 업그레이드 때 함께 검토한다.

## 2. 새 항목 — 파이프라인 단계별 사건 이력

### 왜 필요했나
Airflow 가 보여주는 것은 **Airflow 가 실행하는 태스크**뿐이다(dbt·대조·백업·헬스체크).
실시간 경로는 컨테이너가 돌린다.

| 단계 | 실행 주체 | 오류가 어디 남았나 |
|---|---|---|
| 수집기 → MySQL | 컨테이너 | 컨테이너 로그 |
| Debezium → Kafka | 컨테이너 | 커넥터 상태 API |
| Flink → ClickHouse | 컨테이너 | Flink UI · DLQ 토픽 |
| dbt · 대조 · 백업 | **Airflow** | **Airflow 로그** |

`health_check` 가 10분마다 찔러보지만 그것은 **지금 상태 스냅샷**이지 이력이 아니다.
그래서 "어제 새벽에 **어느 단계가** 왜 깨졌나"를 한 곳에서 볼 수 없었다.

### 무엇을 만들었나
- `cdc_pipeline.pipeline_incidents` — `detected_at · stage · component · severity · title · detail · source · dedup_key`, 180일 보존
- `health_check` 가 이상을 감지하면 **alert_events 와 함께** 여기에도 적는다
  (alert_events = '무엇이 울렸나', pipeline_incidents = '어느 단계가 깨졌나')
- 알럿 이름 → 단계 매핑 `INCIDENT_STAGE`. **모르는 이름은 `unknown`** — 아는 척하지 않는다
- 대시보드 ⑦ 섹션: 최근 사건 30건 + 단계별 7일 집계

### 검증
알럿이 안 울리면 이 코드는 안 돈다. **안 도는 코드는 믿을 수 없으므로** 단위 테스트로 증명했다:
실시간 경로 6단계(collect·mysql·debezium·kafka·flink·clickhouse)가 모두 덮이는지,
접두사 매칭이 되는지, 모르는 이름이 `unknown` 이 되는지. DAG 테스트 **17/17**.

---

## 3. 2단계 — 백필 (13:55 ~ 14:10 UTC)

### 문제
증분 모델이 창을 `now()` 로 잘라서 **과거 날짜를 다시 만들 수 없었다.**
Airflow 에서 9월 12일을 재실행해도 오늘 창을 계산했다. 대조·백업은 `{{ ds }}` 를 쓰는데
dbt 만 안 써서 **한 파이프라인 안에서 재실행 가능 여부가 갈렸다.**

### 해법 — 기준 시각을 변수로
`macros/run_window.sql` 의 `run_anchor()`. **기본값이 `now()` 라 평소 동작은 그대로다.**

```
평소   dbt run
백필   dbt run --vars '{"run_date": "2026-09-12"}'
```

Airflow 는 `daily_pipeline` 의 `dbt run`·`dbt test` 에 `{{ ds }}` 를 넘긴다. 렌더링 확인:
```
cd /opt/airflow/dbt && dbt run --profiles-dir /opt/airflow/dbt_profiles --vars '{"run_date": "2026-09-19"}' 2>&1
```

백필 모드에서는 **상한도 건다**(`< 기준일 + 1일`). 상한이 없으면 백필이 오늘까지 쓸어버린다.

### 검증 — 값이 맞는지 직접 집계와 대조
`dq_ingest_daily` 를 `run_date=2026-09-16` 으로 백필하고, 같은 날을 `stg_trades` 에서 직접 세어 비교했다.

| 날짜 | 모델 | 직접 집계 | 일치 |
|---|---|---|---|
| 2026-09-15 | 2,459,434행 / 286마켓 | 2,459,434 / 286 | **일치** |
| 2026-09-16 | 2,027,655 / 287 | 2,027,655 / 287 | **일치** |
| 2026-09-17 | 1,954,961 / 289 | — | **건드리지 않음**(창 밖) |

소요 17초. 평소 모드 `dbt build` **110 PASS**, 백필 모드 **12 PASS**.

### ⑦ `depends_on_past`
`daily_pipeline` 에만 걸었다. 증분 모델은 전날 결과 위에 쌓으므로 **전날이 실패했는데 오늘이 돌면 구멍이 조용히 남는다.**
`health_check` 같은 '지금 상태' DAG 에는 걸지 않는다 — **과거 실패가 현재 점검을 막으면 안 된다.**

### 남은 것
3단계 `dbt_run` 태스크 분할(레이어별 3태스크)은 관측성 개선이고, 없어도 파이프라인이 틀리지 않는다.
포트폴리오 정리 뒤에 한다.
