"""DAG: quality_alerts — 데이터 품질 SLO 판정 (docs/32 §1·§2 ②, 2026-09-20). 매시 :50.

dq 표는 각자 다른 시각에 갱신된다(Upbit 대조 06:35, 일일 16:00, 규칙 01:15, Binance 00:40 UTC). 그래서 매시 최신 '온전한 날' 행을 SLO 와 비교하고,
같은 (규칙, 날) 은 alert_events 의 dedup_key 로 하루 1회만 Slack 에 보낸다. 리포트가 아니라 판정: 위반이면 이름·값·목표·문서 링크.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

from callbacks.slack_callbacks import send_health_alert, task_failure_callback

default_args = {"owner": "calme", "retries": 1, "retry_delay": timedelta(minutes=5), "on_failure_callback": task_failure_callback}

# (이름, SQL, 위반 조건 함수, 메시지 함수, 문서). SQL 은 최신 온전한 날(오늘 UTC 제외) 한 행을 돌려준다.
# 별칭을 day_s 로 두는 이유: ClickHouse 는 SELECT 별칭이 WHERE 의 열 이름을 가려서 `toString(day) AS day ... WHERE day < today()` 가 String vs Date 비교로 500 이 났다(첫 실행).
RULES = [
    ("Upbit Reconcile", "SELECT toString(day) AS day_s, weighted_pct, min_cell_pct, cells_no_rows, worst_cell FROM cdc_pipeline.dq_reconcile_daily WHERE day < today() ORDER BY day DESC LIMIT 1",
     lambda r: float(r["weighted_pct"]) < 99.9 or float(r["min_cell_pct"]) < 99, lambda r: f"{r['day_s']} weighted {r['weighted_pct']}% (≥99.9) min cell {r['min_cell_pct']}% (≥99) worst {r['worst_cell']}", "docs/17"),
    ("Binance Reconcile", "SELECT toString(day) AS day_s, weighted_pct, min_cell_pct, cells_no_rows, cells_above_101, worst_cell FROM cdc_pipeline.dq_binance_reconcile_daily WHERE day < today() ORDER BY day DESC LIMIT 1",
     lambda r: float(r["weighted_pct"]) < 99.9 or int(r["cells_no_rows"]) > 0 or int(r["cells_above_101"]) > 0, lambda r: f"{r['day_s']} weighted {r['weighted_pct']}% no-row cells {r['cells_no_rows']} >101% cells {r['cells_above_101']} worst {r['worst_cell']}", "docs/31"),
    ("Orderbook Gaps", "SELECT toString(day) AS day_s, gap_windows, gap_seconds_total, gap_longest_s, est_lost_snapshots FROM cdc_pipeline.dq_orderbook_gaps_daily WHERE day < today() ORDER BY day DESC LIMIT 1",
     lambda r: int(r["gap_windows"]) > 0, lambda r: f"{r['day_s']} gap windows {r['gap_windows']} total {r['gap_seconds_total']}s longest {r['gap_longest_s']}s est lost {r['est_lost_snapshots']}", "docs/23 §7"),
    ("Rule Parity", "SELECT toString(day) AS day_s, sql_transitions, flink_transitions, matched, parity_ok FROM cdc_pipeline.dq_alert_parity_daily WHERE day < today() ORDER BY day DESC LIMIT 1",
     lambda r: int(r["parity_ok"]) == 0 and int(r["sql_transitions"]) >= 10, lambda r: f"{r['day_s']} sql {r['sql_transitions']} flink {r['flink_transitions']} matched {r['matched']}", "docs/22 §4"),
    ("Ledger 3-way", "SELECT toString(day) AS day_s, sum(ex_my_mismatch) AS ex_my, sum(my_ch_mismatch) AS my_ch, count() AS symbols FROM cdc_pipeline.dq_ledger_daily WHERE day < today() GROUP BY day ORDER BY day DESC LIMIT 1",
     lambda r: int(r["ex_my"]) > 0 or int(r["my_ch"]) > 0, lambda r: f"{r['day_s']} exchange≠mysql {r['ex_my']} mysql≠clickhouse {r['my_ch']} of {r['symbols']} symbols", "docs/28 B-5"),
]


def _judge(**context) -> dict:
    from hooks.clickhouse_hook import ClickHouseHook
    hook = ClickHouseHook(); log = context["ti"].log
    fired, checked, skipped = [], 0, []
    for name, sql, violated, msg, doc in RULES:
        try:
            r = hook.get_first(sql)
        except Exception as e:  # noqa: BLE001
            skipped.append(f"{name}: {e}"); continue
        if not r:
            skipped.append(f"{name}: no row"); continue
        checked += 1
        if violated(r):
            key = f"quality:{name}:{r['day_s']}"
            already = hook.get_scalar(f"SELECT count() FROM cdc_pipeline.alert_events WHERE dedup_key = '{key}'")
            if already and int(already) > 0:
                log.info("%s violated on %s but already alerted", name, r["day_s"]); continue
            fired.append({"name": name, "message": f"{msg(r)} — {doc}", "dedup_key": key})
    if fired:
        send_health_alert([{"name": f"[품질 SLO] {f['name']}", "message": f["message"]} for f in fired])
        now = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        hook.execute("INSERT INTO cdc_pipeline.alert_events FORMAT JSONEachRow\n" + "\n".join(json.dumps(
            {"fired_at": now, "source": "quality_alerts", "name": f["name"], "severity": "daily", "message": f["message"][:500], "dedup_key": f["dedup_key"]}, ensure_ascii=False) for f in fired))
    log.info("checked %d fired %d skipped %s", checked, len(fired), skipped)
    return {"checked": checked, "fired": [f["name"] for f in fired], "skipped": skipped}


with DAG(
    dag_id="quality_alerts",
    default_args=default_args,
    description="데이터 품질 SLO 판정: dq 표 최신 온전한 날 vs 목표, 위반은 하루 1회 Slack (docs/32)",
    schedule="50 * * * *",
    start_date=datetime(2026, 9, 20),
    catchup=False,
    tags=["quality", "alert"],
    max_active_runs=1,
) as dag:
    PythonOperator(task_id="judge_quality", python_callable=_judge)
