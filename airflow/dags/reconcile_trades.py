"""DAG 3: reconcile_trades — 거래소 원장 대비 체결 유실 대조 (일 1회).

무엇을: 전날(UTC) 287마켓 × 24시간에 대해 업비트 REST 시간봉(거래소 진실값)의 거래량과
       ClickHouse 체결(체결시각 기준)의 거래량 합을 비교한다. 비율 < 99% 셀이 있으면 실패.
왜 이 구조인가:
  - Airflow 는 외부 참조값 적재(EL)와 순서·재시도·알림만 담당하고, 비율 계산과 판정은 dbt 모델·테스트에 둔다.
    비즈니스 SQL 이 DAG 안에 있으면 재현·리뷰·버전관리가 안 된다 (daily_pipeline 의 inline SQL 은 이전 방식).
  - 참조값(시간봉)은 테이블 `upbit_hourly_candles` 에 남긴다. 결과만 남기면 나중에 재계산·감사가 불가하다.
  - 대상일 = {{ ds }} (data_interval_start 의 날짜). 스케줄 06:35 UTC 에 ds 는 전날 → 전날 00:00~24:00 UTC 가 완결된 뒤 6.5h 여유.
    catchup=False 지만 `airflow dags backfill -s -e` 로 과거 날짜 재실행 가능 (ReplacingMergeTree 라 멱등).
  - 06:35 UTC 인 이유: 2026-09-10~16 관찰 기간 cron 대조와 같은 시각 → 결과 연속성. 다른 REST 작업(라벨 동기화 :07)과 겹치지 않음.
  - pool `upbit_rest`(1 슬롯): 업비트 REST 한도 초당 10회/IP. DAG 간 동시 호출로 429 가 났던 사고(2026-09-10) 재발 방지.
  - Slack 은 실패(유실 셀 존재·적재 실패)일 때만. 매일 "정상" 발송은 alert fatigue.
근거 문서: docs/13(유실 사고·대조 방법), docs/17(설계 결정).
"""

from __future__ import annotations

import json
import time
from datetime import datetime, timedelta

import requests
from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.utils.trigger_rule import TriggerRule

from callbacks.slack_callbacks import send_health_alert, task_failure_callback

UPBIT_MARKET_URL = "https://api.upbit.com/v1/market/all?is_details=false"
UPBIT_CANDLE_URL = "https://api.upbit.com/v1/candles/minutes/60"
REQUEST_INTERVAL_S = 0.13  # ≤ 8 req/s (한도 10/s 에 여유)
LOSS_THRESHOLD_PCT = 99.0   # docs/13: 수정 후 6일 연속 100.0%. 1% 미만 차이는 거래소 봉 집계 경계 오차 허용


def _target_date(context) -> str:
    return context["params"].get("target_date") or context["ds"]


def _fetch_hourly_candles(**context) -> dict:
    """대상일 24시간봉을 KRW 전 마켓에 대해 받아 upbit_hourly_candles 에 적재한다."""
    from hooks.clickhouse_hook import ClickHouseHook

    day = datetime.strptime(_target_date(context), "%Y-%m-%d")
    to = (day + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    session = requests.Session()
    session.headers["Accept"] = "application/json"

    markets = [m["market"] for m in session.get(UPBIT_MARKET_URL, timeout=15).json() if m["market"].startswith("KRW-")]
    rows, failed = [], []
    for market in markets:
        for attempt in range(4):
            resp = session.get(UPBIT_CANDLE_URL, params={"market": market, "to": to, "count": 24}, timeout=15)
            if resp.status_code == 429:
                time.sleep(1.0 * (attempt + 1))
                continue
            break
        if resp.status_code != 200:
            failed.append(f"{market}:{resp.status_code}")
        else:
            # count=24 는 "존재하는 마지막 24봉"이라 거래가 없던 시간이 있으면 전날로 밀린다 → 대상일 봉만 남긴다
            for c in resp.json():
                if not c["candle_date_time_utc"].startswith(day.strftime("%Y-%m-%d")):
                    continue
                rows.append({
                    "market": market,
                    "hour_utc": c["candle_date_time_utc"].replace("T", " "),
                    "open": c["opening_price"], "high": c["high_price"], "low": c["low_price"], "close": c["trade_price"],
                    "volume": c["candle_acc_trade_volume"], "amount": c["candle_acc_trade_price"],
                })
        time.sleep(REQUEST_INTERVAL_S)

    hook = ClickHouseHook()
    if rows:
        payload = "\n".join(json.dumps(r) for r in rows)
        hook.execute(f"INSERT INTO cdc_pipeline.upbit_hourly_candles FORMAT JSONEachRow\n{payload}")

    summary = {"date": day.strftime("%Y-%m-%d"), "markets": len(markets), "rows": len(rows), "failed": failed}
    context["ti"].log.info("candles: %s", summary)
    if len(failed) > len(markets) * 0.05:  # 5% 넘게 못 받으면 대조 자체가 무의미 → 실패시켜 재시도
        raise RuntimeError(f"candle fetch failed for {len(failed)}/{len(markets)} markets: {failed[:10]}")
    return summary


def _summarize(**context) -> dict:
    """대조 결과 요약. dbt 테스트가 실패했으면(=유실 셀 존재) 셀 목록을 Slack 으로 보낸다."""
    from hooks.clickhouse_hook import ClickHouseHook

    day = _target_date(context)
    hook = ClickHouseHook()
    agg = hook.get_first(f"""
        SELECT round(100 * sum(ch_vol) / sum(candle_vol), 2) AS weighted_pct,
               count() AS cells, countIf(ratio_pct < {LOSS_THRESHOLD_PCT}) AS cells_below,
               uniqExact(market) AS markets
        FROM cdc_pipeline.int_reconcile_hourly
        WHERE toDate(hour_utc) = '{day}' AND candle_vol > 0
    """) or {}
    worst = hook.get_records(f"""
        SELECT market, hour_utc, round(ratio_pct, 2) AS ratio_pct, ch_n
        FROM cdc_pipeline.int_reconcile_hourly
        WHERE toDate(hour_utc) = '{day}' AND candle_vol > 0 AND ratio_pct < {LOSS_THRESHOLD_PCT}
        ORDER BY ratio_pct LIMIT 10
    """)
    upstream_failed = context["dag_run"].get_task_instance("dbt_build_reconcile").state != "success"
    result = {"date": day, **agg, "worst": worst, "dbt_failed": upstream_failed}
    context["ti"].log.info("reconcile summary: %s", result)

    if upstream_failed or int(agg.get("cells_below") or 0) > 0:
        lines = [f"{w['market']} {w['hour_utc']} {w['ratio_pct']}% (rows {w['ch_n']})" for w in worst]
        send_health_alert([{
            "name": f"원장 대조 실패 {day}",
            "message": (f"가중 비율 {agg.get('weighted_pct')}% | 셀 {agg.get('cells')} | "
                        f"{LOSS_THRESHOLD_PCT}% 미만 {agg.get('cells_below')} | dbt 실패 {upstream_failed}\n"
                        + "\n".join(lines[:10])),
        }])
        raise RuntimeError("reconcile failed — see Slack / int_reconcile_hourly")
    return result


default_args = {
    "owner": "calme",
    "retries": 3,
    "retry_delay": timedelta(minutes=3),
    "retry_exponential_backoff": True,
    "on_failure_callback": task_failure_callback,
}

with DAG(
    dag_id="reconcile_trades",
    default_args=default_args,
    description="업비트 시간봉(진실값) vs ClickHouse 체결 거래량 일일 대조 — 유실 0 증명",
    schedule="35 6 * * *",
    start_date=datetime(2026, 9, 10),
    catchup=False,
    max_active_runs=1,
    tags=["data-quality", "reconcile", "dbt"],
    params={"target_date": ""},
    doc_md=__doc__,
) as dag:

    fetch_hourly_candles = PythonOperator(
        task_id="fetch_hourly_candles",
        python_callable=_fetch_hourly_candles,
        pool="upbit_rest",
        execution_timeout=timedelta(minutes=15),
    )

    # dbt build = run + test. 모델 int_reconcile_hourly 와 그 모델을 참조하는 테스트(유실·커버리지)가 함께 실행된다.
    dbt_build_reconcile = BashOperator(
        task_id="dbt_build_reconcile",
        bash_command=(
            "cd /opt/airflow/dbt && dbt build --select +int_reconcile_hourly "
            "--vars '{\"reconcile_date\": \"{{ params.target_date or ds }}\"}' "
            "--profiles-dir /opt/airflow/dbt_profiles 2>&1"
        ),
        retries=0,  # 테스트 실패는 재시도해도 같은 결과 → 요약 태스크로 넘겨 알림
    )

    summarize = PythonOperator(
        task_id="summarize",
        python_callable=_summarize,
        trigger_rule=TriggerRule.ALL_DONE,  # dbt 가 실패해도 요약·알림은 수행
        retries=0,
    )

    fetch_hourly_candles >> dbt_build_reconcile >> summarize
