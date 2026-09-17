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
    schedule="*/10 * * * *",
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
        expected_jobs=3,  # 2026-09-09: CDC + Circuit + Orderbook
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

    # ── 적재 지연 확인 (2026-09-09 추가) ─────────────────────
    # source_ts(MySQL 적재 시각) - upbit_timestamp(거래소 체결 시각).
    # 2026-08-19~30 producer 상한 포화로 최대 36.9시간 지연이 났으나 기존 체크는
    # 전부 source_ts 이후 구간만 봐서 감지 못했음 (docs/08-ingest-lag-incident.md).
    check_ingest_lag = ClickHouseOperator(
        task_id="check_ingest_lag",
        sql="""
            SELECT
                round(quantile(0.5)(toUnixTimestamp64Milli(source_ts) - upbit_timestamp) / 1000, 1) AS lag_p50_s,
                round(max(toUnixTimestamp64Milli(source_ts) - upbit_timestamp) / 1000, 1) AS lag_max_s,
                count() AS rows_10min
            FROM cdc_pipeline.crypto_trades
            WHERE source_ts >= now() - INTERVAL 10 MINUTE
        """,
        result_type="first",
    )

    # ── 마켓별 커버리지 (2026-09-16 추가, docs/19 #8) ─────────
    # 배경: KRW-BFC 상장 후 6일 무수집을 하루 뒤 대조로만 알았다. 감지를 10분으로 당긴다.
    # 방법: 거래소 ticker(한 호출, 전 마켓)의 마지막 체결 시각 vs 우리 마켓별 마지막 체결 시각.
    #       우리가 최신 체결을 갖고 있으면 차이는 적재 지연(관찰 주간 최대 7.75초)뿐이다.
    # 판정: 거래소가 60초 넘게 전에 본 마지막 체결이 우리에게 없다 (ex_ms > ours_last AND now − ex_ms > 60s).
    #   첫 실행(09-16 22:25)에서 배운 것 — "거래소 마지막 − 우리 마지막" 차이로 재면, 체결 직후 1~2초 사이에 체크가 돌 때
    #   아직 적재되기 전의 직전 체결과 비교되어 뜸한 마켓(KRW-G)이 263초 뒤처진 것으로 나온다. 최신 체결에 도착할 시간(60초)을
    #   준 뒤에도 없어야 누락이다.
    # 임계 60초: 정상 적재 지연(관찰 주간 최대 7.75초)의 8배이고, 늦은 이벤트 가드·적재 지연 알림과 같은 "실시간 아님" 기준(docs/20).
    # 대상: 거래소 마지막 체결이 최근 24시간 안인 마켓만 (하루 이상 거래 없는 마켓은 비교 대상 아님).
    # 재연결 직후 gap-fill 이 끝나기 전 1회 걸릴 수 있다 — 그 알림은 실제 상태이므로 억제하지 않는다.
    def _check_market_coverage(**context) -> dict:
        import time
        import requests
        from hooks.clickhouse_hook import ClickHouseHook

        markets = [m["market"] for m in requests.get("https://api.upbit.com/v1/market/all?is_details=false", timeout=15).json()
                   if m["market"].startswith("KRW-")]
        ticker = requests.get("https://api.upbit.com/v1/ticker", params={"markets": ",".join(markets)}, timeout=15).json()
        exchange_last = {t["market"]: int(t["trade_timestamp"]) for t in ticker}

        rows = ClickHouseHook().get_records("""
            SELECT market, max(upbit_timestamp) AS last_ms
            FROM cdc_pipeline.crypto_trades
            WHERE source_ts >= now() - INTERVAL 1 DAY
            GROUP BY market
        """)
        ours_last = {r["market"]: int(r["last_ms"]) for r in rows}

        now_ms = int(time.time() * 1000)
        missing = []
        for market, ex_ms in exchange_last.items():
            if now_ms - ex_ms > 86_400_000:
                continue
            if ex_ms > ours_last.get(market, 0) and now_ms - ex_ms > 60_000:
                missing.append({"market": market, "behind_s": round((now_ms - ex_ms) / 1000), "ours": market in ours_last})
        missing.sort(key=lambda x: -x["behind_s"])
        result = {"checked": len(exchange_last), "missing": missing[:20], "missing_count": len(missing)}
        context["ti"].log.info("market coverage: %s", result)
        return result

    check_market_coverage = PythonOperator(
        task_id="check_market_coverage",
        python_callable=_check_market_coverage,
        pool="upbit_rest",
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
    # ── 포화 선행 지표 (2026-09-17 추가, docs/23 §5 부하 실험) ─────────
    # 실험에서 임계(10,000/s)에 닿기 전에 먼저 움직인 지표 둘: ① Flink 소스 체인 busy(500→5,000/s 에서 48→240 ms/s 선형, 임계에서 1,000),
    # ② ClickHouse insert 평균 지연(기준선 9ms → 24ms 부터 정체 시작). 프로덕션 기준선: busy 3 ms/s, insert 평균 14~18 ms(24h 시간별).
    # 임계: busy > 500 ms/s(포화의 절반 — 실험에서 651 은 이미 정체), insert 평균 > 30 ms(기준선 2배). 둘 다 10분 창.
    # 백프레셔는 쓰지 않는다: 소스가 체인의 머리라 10,000/s 에서도 0 이었다(docs/23 §5).
    def _check_source_busy(**context) -> dict:
        import requests
        base = "http://flink-jobmanager:8081"
        jobs = requests.get(f"{base}/jobs/overview", timeout=10).json()["jobs"]
        js = [j for j in jobs if j["name"] == "CDC Realtime Pipeline" and j["state"] == "RUNNING"]
        if not js:
            return {"busy_max_ms": None, "error": "prod CDC job not running"}
        jid = js[0]["jid"]
        vertices = requests.get(f"{base}/jobs/{jid}", timeout=10).json()["vertices"]
        src = [v for v in vertices if v["name"].startswith("Source")][0]["id"]
        m = requests.get(f"{base}/jobs/{jid}/vertices/{src}/subtasks/metrics",
                         params={"get": "busyTimeMsPerSecond", "agg": "max"}, timeout=10).json()
        busy = float(m[0]["max"]) if m else None
        return {"busy_max_ms": busy, "job_id": jid}

    check_source_busy = PythonOperator(
        task_id="check_source_busy",
        python_callable=_check_source_busy,
    )

    check_insert_latency = ClickHouseOperator(
        task_id="check_insert_latency",
        sql="""
            SELECT
                round(avg(query_duration_ms), 1) AS insert_avg_ms,
                round(quantile(0.95)(query_duration_ms)) AS insert_p95_ms,
                count() AS inserts_10min
            FROM system.query_log
            WHERE type = 'QueryFinish' AND query_kind = 'Insert'
              AND has(tables, 'cdc_pipeline.crypto_trades')
              AND event_time >= now() - INTERVAL 10 MINUTE
        """,
        result_type="first",
    )

    def _evaluate_health(**context) -> dict:
        ti = context["ti"]

        ch_result = ti.xcom_pull(task_ids="check_clickhouse_ingest")
        flink_result = ti.xcom_pull(task_ids="check_flink_jobs")
        kafka_result = ti.xcom_pull(task_ids="check_kafka_health")
        producer_result = ti.xcom_pull(task_ids="check_producer_activity")
        connect_result = ti.xcom_pull(task_ids="check_kafka_connect")
        lag_result = ti.xcom_pull(task_ids="check_ingest_lag")
        coverage_result = ti.xcom_pull(task_ids="check_market_coverage")
        busy_result = ti.xcom_pull(task_ids="check_source_busy")
        insert_result = ti.xcom_pull(task_ids="check_insert_latency")

        unhealthy = []

        # 포화 선행 지표 (docs/23 §5): 유실 전에, 실시간이 깨지기 전에 알린다
        if busy_result and busy_result.get("busy_max_ms") is not None and float(busy_result["busy_max_ms"]) > 500:
            unhealthy.append({"name": "Flink Source Saturation",
                              "message": f"source busy max {busy_result['busy_max_ms']:.0f} ms/s (> 500; 1,000 = 포화, 기준선 3)"})
        if insert_result and insert_result.get("insert_avg_ms") is not None and int(insert_result.get("inserts_10min", 0)) > 0 \
                and float(insert_result["insert_avg_ms"]) > 30:
            unhealthy.append({"name": "ClickHouse Insert Latency",
                              "message": f"insert avg {insert_result['insert_avg_ms']} ms / p95 {insert_result['insert_p95_ms']} ms over 10 min (> 30; 기준선 14~18)"})

        # 마켓 커버리지: 거래소에는 최신 체결이 있는데 우리에게 60초 넘게 없는 마켓
        if coverage_result and int(coverage_result.get("missing_count", 0)) > 0:
            top = ", ".join(f"{m['market']}({m['behind_s']}s{'' if m['ours'] else ', 무수집'})" for m in coverage_result["missing"][:8])
            unhealthy.append(
                {
                    "name": "Market Coverage",
                    "message": f"{coverage_result['missing_count']}/{coverage_result['checked']} markets behind exchange: {top}",
                }
            )

        # 적재 지연: 최근 10분 p50 > 60초면 producer 포화 (max는 참고 표기)
        if lag_result and int(lag_result.get("rows_10min", 0)) > 0:
            lag_p50 = float(lag_result.get("lag_p50_s", 0))
            lag_max = float(lag_result.get("lag_max_s", 0))
            if lag_p50 > 60:
                unhealthy.append(
                    {
                        "name": "Ingest Lag",
                        "message": f"source_ts - upbit_ts p50 {lag_p50}s (max {lag_max}s) in last 10min — producer backlog",
                    }
                )

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
            check_ingest_lag,
            check_market_coverage,
            check_source_busy,
            check_insert_latency,
        ]
        >> evaluate_health
    )
