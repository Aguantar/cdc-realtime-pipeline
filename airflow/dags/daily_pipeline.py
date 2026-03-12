"""DAG 2: daily_pipeline — 일일 배치 파이프라인 오케스트레이션.

dbt 실행 → 동적 코인별 품질검증 → quality gate → 일일 리포트.
스케줄: 매일 01:00 KST — 전날(00:00~23:59 KST) 데이터를 집계하여 Slack 리포트.

핵심 Airflow 기능:
- Jinja 템플릿 + target_date: idempotent, backfill 가능
- Dynamic Task Mapping: 코인 목록 동적 로딩 → 코인별 품질검증 병렬
- XCom: 태스크 간 검증 결과 전달
- Dataset: dbt 완료 시 후속 품질검증 트리거
- SLA: dbt가 2시간 이내 완료되지 않으면 알림
- Callback: 실패 시 컨텍스트 포함 Slack 알림
"""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG, Dataset
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.macros import ds_add

from callbacks.slack_callbacks import (
    send_daily_report,
    sla_miss_callback,
    task_failure_callback,
)
from operators.clickhouse_operator import ClickHouseOperator

# Dataset: dbt 완료를 나타내는 논리적 데이터셋
DBT_COMPLETED = Dataset("clickhouse://cdc_pipeline/dbt_models")


def _get_target_date(context) -> str:
    """대상 날짜를 결정합니다.

    스케줄 0 16 * * * (01:00 KST)에서 ds = data_interval_start = 전날 날짜.
    즉 ds가 그대로 리포트 대상일입니다.
    수동 트리거 시 params.target_date로 오버라이드 가능.
    """
    conf_date = context.get("params", {}).get("target_date")
    if conf_date:
        return conf_date
    return context["ds"]

default_args = {
    "owner": "calme",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "on_failure_callback": task_failure_callback,
}

with DAG(
    dag_id="daily_pipeline",
    default_args=default_args,
    description="dbt → 코인별 품질검증(동적) → quality gate → 일일 리포트",
    schedule_interval="0 16 * * *",  # 01:00 KST = 16:00 UTC (전날 데이터 집계)
    start_date=datetime(2026, 3, 11),
    catchup=False,
    tags=["dbt", "data-quality", "report"],
    max_active_runs=1,
    sla_miss_callback=sla_miss_callback,
    params={"target_date": ""},  # 수동 트리거 시 날짜 지정 가능 (빈값=오늘 KST)
) as dag:

    # ── Step 1: dbt run ──────────────────────────────────────
    dbt_run = BashOperator(
        task_id="dbt_run",
        bash_command=(
            "cd /opt/airflow/dbt && "
            "dbt run --profiles-dir /opt/airflow/dbt_profiles 2>&1"
        ),
        sla=timedelta(hours=2),
    )

    # ── Step 2: dbt test ─────────────────────────────────────
    dbt_test = BashOperator(
        task_id="dbt_test",
        bash_command=(
            "cd /opt/airflow/dbt && "
            "dbt test --profiles-dir /opt/airflow/dbt_profiles 2>&1"
        ),
        outlets=[DBT_COMPLETED],  # Dataset 트리거
    )

    # ── Step 3: 코인 목록 동적 로딩 ──────────────────────────
    def _get_coin_list(**context) -> list[str]:
        """ClickHouse에서 활성 코인 목록을 동적으로 가져옵니다."""
        from hooks.clickhouse_hook import ClickHouseHook

        target_date = _get_target_date(context)
        hook = ClickHouseHook()
        result = hook.get_records(
            f"""
            SELECT DISTINCT market
            FROM cdc_pipeline.crypto_trades
            WHERE toDate(source_ts) = '{target_date}'
            ORDER BY market
            """
        )
        coins = [row["market"] for row in result]
        context["ti"].log.info("Target date: %s, Found %d active coins: %s", target_date, len(coins), coins)
        return coins

    get_coins = PythonOperator(
        task_id="get_coin_list",
        python_callable=_get_coin_list,
    )

    # ── Step 4: 코인별 품질검증 (Dynamic Task Mapping) ───────
    def _validate_coin(coin: str, **context) -> dict:
        """개별 코인의 데이터 품질을 검증합니다."""
        from hooks.clickhouse_hook import ClickHouseHook

        target_date = _get_target_date(context)
        hook = ClickHouseHook()
        issues = []

        # 4-1. 일일 거래 건수
        count_result = hook.get_scalar(
            f"SELECT count() FROM cdc_pipeline.crypto_trades "
            f"WHERE market = '{coin}' AND toDate(source_ts) = '{target_date}'"
        )
        trade_count = int(count_result or 0)
        if trade_count == 0:
            issues.append(f"No trades on {target_date}")
        elif trade_count < 100:
            issues.append(f"Low trade count: {trade_count}")

        # 4-2. NULL 비율 (trade_price)
        null_result = hook.get_scalar(
            f"SELECT round(countIf(trade_price = 0 OR trade_price IS NULL) * 100.0 "
            f"/ count(), 2) FROM cdc_pipeline.crypto_trades "
            f"WHERE market = '{coin}' AND toDate(source_ts) = '{target_date}'"
        )
        null_pct = float(null_result or 0)
        if null_pct > 1.0:
            issues.append(f"Null/zero price ratio: {null_pct}%")

        # 4-3. CDC 지연 이상치 (1초 초과)
        latency_result = hook.get_scalar(
            f"SELECT countIf(cdc_latency_ms > 1000) FROM cdc_pipeline.crypto_trades "
            f"WHERE market = '{coin}' AND toDate(source_ts) = '{target_date}'"
        )
        high_latency = int(latency_result or 0)
        if high_latency > 100:
            issues.append(f"High latency events (>1s): {high_latency}")

        result = {
            "coin": coin,
            "date": target_date,
            "trade_count": trade_count,
            "null_pct": null_pct,
            "high_latency_count": high_latency,
            "passed": len(issues) == 0,
            "issues": issues,
        }
        context["ti"].log.info("Validation [%s] %s: %s", target_date, coin, result)
        return result

    validate_coins = PythonOperator.partial(
        task_id="validate_coin",
        python_callable=_validate_coin,
    ).expand(op_kwargs=get_coins.output.map(lambda coin: {"coin": coin}))

    # ── Step 5: Quality Gate (XCom 수집 → 종합 판단) ─────────
    def _quality_gate(**context) -> dict:
        """모든 코인의 검증 결과를 수집하여 종합 pass/fail 판단."""
        ti = context["ti"]
        raw_results = ti.xcom_pull(task_ids="validate_coin")

        if not raw_results:
            return {"passed": 0, "failed": 0, "total": 0, "failed_coins": []}

        # Dynamic task mapping의 XCom은 LazyXComAccess → list로 변환
        results = list(raw_results)

        passed = [r for r in results if isinstance(r, dict) and r.get("passed")]
        failed = [r for r in results if isinstance(r, dict) and not r.get("passed")]

        gate_result = {
            "passed": len(passed),
            "failed": len(failed),
            "total": len(results),
            "failed_coins": [
                {"coin": r["coin"], "issues": r["issues"]} for r in failed
            ],
            "pass_rate": round(len(passed) / len(results) * 100, 1)
            if results
            else 0,
        }

        ti.log.info(
            "Quality Gate: %d/%d passed (%.1f%%)",
            gate_result["passed"],
            gate_result["total"],
            gate_result["pass_rate"],
        )

        if failed:
            ti.log.warning("Failed coins: %s", gate_result["failed_coins"])

        return gate_result

    quality_gate = PythonOperator(
        task_id="quality_gate",
        python_callable=_quality_gate,
    )

    # ── Step 6: 중복 체크 ────────────────────────────────────
    def _check_duplicates(**context) -> list:
        """trade_id 중복 건수를 확인합니다."""
        from hooks.clickhouse_hook import ClickHouseHook

        target_date = _get_target_date(context)
        hook = ClickHouseHook()
        return hook.get_records(
            f"""
            SELECT trade_id, count() AS cnt
            FROM cdc_pipeline.crypto_trades
            WHERE toDate(source_ts) = '{target_date}'
            GROUP BY trade_id
            HAVING cnt > 1
            LIMIT 10
            """
        )

    check_duplicates = PythonOperator(
        task_id="check_duplicates",
        python_callable=_check_duplicates,
    )

    # ── Step 7: 일일 요약 리포트 생성 ────────────────────────
    def _generate_report(**context) -> dict:
        """ClickHouse mart에서 일일 요약 데이터를 조회합니다."""
        from hooks.clickhouse_hook import ClickHouseHook

        target_date = _get_target_date(context)
        hook = ClickHouseHook()

        summary = hook.get_records(
            f"""
            SELECT
                market,
                trade_count,
                round(close, 0) AS close_price,
                round(volume, 4) AS total_volume,
                round(amount, 0) AS total_amount,
                round(close_change_pct, 2) AS close_change_pct,
                round(volume_change_pct, 2) AS volume_change_pct
            FROM cdc_pipeline.mart_daily_summary
            WHERE trade_date = '{target_date}'
            ORDER BY amount DESC
            """
        )

        volume_spikes = hook.get_records(
            f"""
            SELECT market, hour_start, round(volume_ratio, 1) AS volume_ratio
            FROM cdc_pipeline.mart_volume_spike
            WHERE toDate(hour_start) = '{target_date}'
            ORDER BY volume_ratio DESC
            LIMIT 5
            """
        )

        # CDC 지연 통계 (p50/p95/p99/max)
        latency_stats = hook.get_records(
            f"""
            SELECT
                round(quantile(0.5)(cdc_latency_ms), 1) AS p50,
                round(quantile(0.95)(cdc_latency_ms), 1) AS p95,
                round(quantile(0.99)(cdc_latency_ms), 1) AS p99,
                round(max(cdc_latency_ms), 1) AS max_val
            FROM cdc_pipeline.crypto_trades
            WHERE toDate(source_ts) = '{target_date}'
            """
        )
        latency = {}
        if latency_stats:
            row = latency_stats[0]
            latency = {
                "p50": row.get("p50", "?"),
                "p95": row.get("p95", "?"),
                "p99": row.get("p99", "?"),
                "max": row.get("max_val", "?"),
            }

        # 이상 탐지 알림 건수 (유형별)
        anomaly_rows = hook.get_records(
            f"""
            SELECT alert_type, count() AS cnt
            FROM cdc_pipeline.anomaly_alerts
            WHERE toDate(detected_at) = '{target_date}'
            GROUP BY alert_type
            """
        )
        anomaly_counts = {
            row["alert_type"]: int(row["cnt"]) for row in anomaly_rows
        }

        return {
            "date": target_date,
            "summary": summary,
            "volume_spikes": volume_spikes,
            "latency_stats": latency,
            "anomaly_counts": anomaly_counts,
            "coin_count": len(summary),
        }

    generate_report = PythonOperator(
        task_id="generate_report",
        python_callable=_generate_report,
    )

    # ── Step 8: Slack 일일 리포트 발송 ───────────────────────
    def _send_report(**context) -> None:
        """품질 검증 + 일일 요약을 종합하여 Slack으로 전송."""
        ti = context["ti"]
        quality = ti.xcom_pull(task_ids="quality_gate")
        report = ti.xcom_pull(task_ids="generate_report")
        duplicates = ti.xcom_pull(task_ids="check_duplicates")

        # 파이프라인 실행 시간 계산
        dag_run = context.get("dag_run")
        exec_seconds = "?"
        if dag_run and dag_run.start_date:
            from datetime import datetime, timezone
            now = datetime.now(timezone.utc)
            exec_seconds = int((now - dag_run.start_date).total_seconds())

        target_date = _get_target_date(context)
        report_data = {
            "date": target_date,
            "quality": quality,
            "summary": report.get("summary", []) if report else [],
            "volume_spikes": report.get("volume_spikes", []) if report else [],
            "latency_stats": report.get("latency_stats", {}) if report else {},
            "anomaly_counts": report.get("anomaly_counts", {}) if report else {},
            "duplicates_found": len(duplicates) if duplicates else 0,
            "execution_seconds": exec_seconds,
        }

        send_daily_report(report_data)
        ti.log.info("Daily report sent to Slack")

    slack_report = PythonOperator(
        task_id="slack_daily_report",
        python_callable=_send_report,
    )

    # ── DAG 의존성 ───────────────────────────────────────────
    dbt_run >> dbt_test >> get_coins >> validate_coins >> quality_gate
    dbt_test >> check_duplicates
    quality_gate >> generate_report >> slack_report
    check_duplicates >> slack_report
