"""DAG 1: health_check — CDC 파이프라인 헬스체크 (10분 간격).

Custom Operator(ClickHouseOperator, FlinkHealthOperator)를 사용하여
파이프라인 컴포넌트 상태를 확인하고, XCom으로 결과를 전달하여 종합 판단합니다.
이상 발견 시 Slack으로 컨텍스트 포함 알림을 전송합니다.

모든 체크는 네트워크 API 기반 (Docker 소켓 불필요).
"""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

from callbacks.slack_callbacks import send_health_alert, task_failure_callback
from operators.clickhouse_operator import ClickHouseOperator
from operators.flink_health_operator import FlinkHealthOperator

default_args = {
    "owner": "calme",
    "retries": 1,
    "retry_delay": timedelta(minutes=2),
    "on_failure_callback": task_failure_callback,
}

with DAG(
    dag_id="health_check",
    default_args=default_args,
    description="CDC 파이프라인 전체 컴포넌트 헬스체크 (10분 간격)",
    schedule_interval="*/10 * * * *",
    start_date=datetime(2026, 3, 11),
    catchup=False,
    tags=["monitoring", "health"],
    max_active_runs=1,
) as dag:

    # ── ClickHouse 데이터 적재 확인 ──────────────────────────
    check_clickhouse_ingest = ClickHouseOperator(
        task_id="check_clickhouse_ingest",
        sql="""
            SELECT
                count() AS recent_count,
                max(source_ts) AS latest_ts,
                dateDiff('minute', max(source_ts), now()) AS minutes_since_last
            FROM cdc_pipeline.crypto_trades
            WHERE source_ts >= now() - INTERVAL 10 MINUTE
        """,
        result_type="first",
    )

    # ── Flink 잡 상태 확인 (REST API) ────────────────────────
    check_flink_jobs = FlinkHealthOperator(
        task_id="check_flink_jobs",
        flink_base_url="http://flink-jobmanager:8081",
        expected_jobs=2,
    )

    # ── Kafka 브로커 상태 확인 (ClickHouse 기반 간접 확인) ───
    check_kafka_health = ClickHouseOperator(
        task_id="check_kafka_health",
        sql="""
            SELECT
                count() AS recent_count,
                uniqExact(market) AS active_markets
            FROM cdc_pipeline.crypto_trades
            WHERE source_ts >= now() - INTERVAL 5 MINUTE
        """,
        result_type="first",
    )

    # ── Producer 상태 확인 (데이터 유입 기반 간접 확인) ───────
    check_producer_activity = ClickHouseOperator(
        task_id="check_producer_activity",
        sql="""
            SELECT
                count() AS last_1min_count,
                uniqExact(market) AS active_markets
            FROM cdc_pipeline.crypto_trades
            WHERE source_ts >= now() - INTERVAL 1 MINUTE
        """,
        result_type="first",
    )

    # ── Kafka Connect 상태 확인 (REST API) ───────────────────
    def _check_kafka_connect(**context) -> dict:
        """Kafka Connect REST API로 커넥터 상태를 확인합니다."""
        import requests as req

        try:
            resp = req.get(
                "http://kafka-connect:8083/connectors", timeout=10
            )
            resp.raise_for_status()
            connectors = resp.json()

            statuses = {}
            for name in connectors:
                status_resp = req.get(
                    f"http://kafka-connect:8083/connectors/{name}/status",
                    timeout=10,
                )
                status_resp.raise_for_status()
                status = status_resp.json()
                connector_state = status.get("connector", {}).get("state", "UNKNOWN")
                tasks_state = [
                    t.get("state", "UNKNOWN")
                    for t in status.get("tasks", [])
                ]
                statuses[name] = {
                    "connector": connector_state,
                    "tasks": tasks_state,
                }

            all_running = all(
                s["connector"] == "RUNNING"
                and all(t == "RUNNING" for t in s["tasks"])
                for s in statuses.values()
            )

            return {
                "healthy": all_running,
                "connectors": statuses,
                "count": len(connectors),
            }
        except req.RequestException as e:
            return {"healthy": False, "error": str(e), "connectors": {}, "count": 0}

    check_connect = PythonOperator(
        task_id="check_kafka_connect",
        python_callable=_check_kafka_connect,
    )

    # ── 종합 판단 (XCom 수집) ────────────────────────────────
    def _evaluate_health(**context) -> dict:
        ti = context["ti"]

        ch_result = ti.xcom_pull(task_ids="check_clickhouse_ingest")
        flink_result = ti.xcom_pull(task_ids="check_flink_jobs")
        kafka_result = ti.xcom_pull(task_ids="check_kafka_health")
        producer_result = ti.xcom_pull(task_ids="check_producer_activity")
        connect_result = ti.xcom_pull(task_ids="check_kafka_connect")

        unhealthy = []

        # ClickHouse: 최근 10분간 데이터 없으면 이상
        if ch_result:
            recent_count = int(ch_result.get("recent_count", 0))
            minutes_since = int(ch_result.get("minutes_since_last", 999))
            if recent_count == 0 or minutes_since > 15:
                unhealthy.append(
                    {
                        "name": "ClickHouse Ingest",
                        "message": f"Recent 10min: {recent_count} rows, last data: {minutes_since}min ago",
                    }
                )
        else:
            unhealthy.append(
                {"name": "ClickHouse", "message": "Query returned no result"}
            )

        # Flink: RUNNING 잡 수 부족
        if flink_result and not flink_result.get("healthy", False):
            running = flink_result.get("running_jobs", 0)
            expected = flink_result.get("expected_jobs", 2)
            job_details = ", ".join(
                f"{j['name']}({j['state']})" for j in flink_result.get("jobs", [])
            )
            unhealthy.append(
                {
                    "name": "Flink Jobs",
                    "message": f"Running: {running}/{expected}. Jobs: {job_details}",
                }
            )

        # Kafka: 데이터 유입 기반 확인
        if kafka_result:
            active_markets = int(kafka_result.get("active_markets", 0))
            if active_markets < 5:
                unhealthy.append(
                    {
                        "name": "Kafka/Pipeline",
                        "message": f"Only {active_markets} markets active in last 5min (expected 20+)",
                    }
                )

        # Producer: 최근 1분 데이터 유입 확인
        if producer_result:
            last_1min = int(producer_result.get("last_1min_count", 0))
            if last_1min == 0:
                unhealthy.append(
                    {
                        "name": "Upbit Producer",
                        "message": "No data in last 1 minute",
                    }
                )

        # Kafka Connect: 커넥터 상태
        if connect_result and not connect_result.get("healthy", False):
            error = connect_result.get("error", "")
            connectors = connect_result.get("connectors", {})
            failed = [
                name
                for name, s in connectors.items()
                if s.get("connector") != "RUNNING"
            ]
            msg = f"Failed connectors: {failed}" if failed else f"Error: {error}"
            unhealthy.append({"name": "Kafka Connect", "message": msg})

        if unhealthy:
            send_health_alert(unhealthy)
            ti.log.warning("Health check FAILED: %s", unhealthy)
        else:
            ti.log.info("All components healthy")

        return {"healthy": len(unhealthy) == 0, "issues": unhealthy}

    evaluate_health = PythonOperator(
        task_id="evaluate_health",
        python_callable=_evaluate_health,
    )

    (
        [
            check_clickhouse_ingest,
            check_flink_jobs,
            check_kafka_health,
            check_producer_activity,
            check_connect,
        ]
        >> evaluate_health
    )
