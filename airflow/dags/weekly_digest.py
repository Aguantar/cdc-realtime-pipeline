"""DAG: weekly_digest — 주간 운영 다이제스트 (docs/32 §2 ③, 2026-09-20). 월요일 00:00 UTC(09:00 KST).

개인 규모의 "뒷단": 사람이 계획을 세우는 재료. 지난 7일의
  ① 트래픽 프로필(시간대별 유입, 피크 시각) ② 지연 추세 ③ 울린 알럿 집계(반복 = 임계 재검토) ④ 품질 추세(대조·동등성)
  ⑤ 자원(load·컨테이너 메모리·디스크 가득 차기까지 일수) ⑥ 고칠 것 — 규칙으로 뽑은 목록.
Slack 으로 보내고 같은 본문을 ops_digest 에 남긴다.
"""
from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

from callbacks.slack_callbacks import send_text_report, task_failure_callback

default_args = {"owner": "calme", "retries": 1, "retry_delay": timedelta(minutes=5), "on_failure_callback": task_failure_callback}


def _q(hook, sql):
    try:
        return hook.get_records(sql)
    except Exception as e:  # noqa: BLE001
        return [{"error": str(e)[:120]}]


def _build(**context) -> dict:
    from hooks.clickhouse_hook import ClickHouseHook
    hook = ClickHouseHook(); lines = []; fixes = []
    # ① 트래픽 프로필 (KST 시간대별, 7일 평균 rows/s)
    prof = _q(hook, """SELECT toHour(flink_ts + INTERVAL 9 HOUR) AS h_kst, round(count()/7/3600, 1) AS upbit_rps FROM cdc_pipeline.crypto_trades
                       WHERE flink_ts >= now() - INTERVAL 7 DAY GROUP BY h_kst ORDER BY upbit_rps DESC LIMIT 3""")
    bprof = _q(hook, """SELECT toHour(flink_ts + INTERVAL 9 HOUR) AS h_kst, round(count()/uniqExact(toDate(flink_ts))/3600, 1) AS rps FROM cdc_pipeline.binance_trades
                        WHERE flink_ts >= now() - INTERVAL 7 DAY GROUP BY h_kst ORDER BY rps DESC LIMIT 3""")
    lines.append("*① 트래픽 피크(KST 시각, rows/s)*  Upbit: " + ", ".join(f"{r.get('h_kst')}시 {r.get('upbit_rps')}" for r in prof) + " | Binance: " + ", ".join(f"{r.get('h_kst')}시 {r.get('rps')}" for r in bprof))
    # ② 지연 추세 (일별 e2e p95)
    # 실시간 행만: 백필·gap-fill 행은 체결 시각이 하루 넘게 과거라 p95 를 망친다(09-16 512,082s 실측) → 1시간 넘게 늦은 행 제외
    lat = _q(hook, """SELECT toString(toDate(flink_ts)) AS d, round(quantile(0.95)(toUnixTimestamp64Milli(flink_ts)-upbit_timestamp)/1000, 2) AS p95 FROM cdc_pipeline.crypto_trades
                      WHERE flink_ts >= now() - INTERVAL 7 DAY AND toUnixTimestamp64Milli(flink_ts) - upbit_timestamp < 3600000 GROUP BY d ORDER BY d""")
    lines.append("*② 체결 e2e p95(s), 일별*  " + " → ".join(f"{r.get('d','')[5:]} {r.get('p95')}" for r in lat))
    if lat and all('p95' in r for r in lat) and float(lat[-1]["p95"]) > 6: fixes.append(f"e2e p95 {lat[-1]['p95']}s > 6s: Flink 배치 간격·ClickHouse 머지 확인(docs/23)")
    # ③ 알럿 집계
    al = _q(hook, """SELECT name, count() AS n, uniqExact(dedup_key) AS distinct_hours FROM cdc_pipeline.alert_events WHERE fired_at >= now() - INTERVAL 7 DAY GROUP BY name ORDER BY n DESC LIMIT 8""")
    lines.append("*③ 울린 알럿(7일)*  " + (", ".join(f"{r.get('name')} ×{r.get('n')}" for r in al) if al else "없음"))
    for r in al:
        if 'n' in r and int(r["n"]) >= 10: fixes.append(f"'{r['name']}' 이 7일간 {r['n']}회: 임계가 기준선과 안 맞거나 만성 문제 — 임계 재검토(docs/32 §1)")
    # ④ 품질 추세
    rq = _q(hook, "SELECT toString(day) AS d, weighted_pct AS p FROM cdc_pipeline.dq_reconcile_daily WHERE day >= today() - 7 ORDER BY day")
    bq = _q(hook, "SELECT toString(day) AS d, weighted_pct AS p FROM cdc_pipeline.dq_binance_reconcile_daily WHERE day >= today() - 7 ORDER BY day")
    pq = _q(hook, "SELECT toString(day) AS d, parity_ok AS ok, matched AS m FROM cdc_pipeline.dq_alert_parity_daily WHERE day >= today() - 7 AND day < today() ORDER BY day")
    lines.append("*④ 대조(%)*  Upbit " + " ".join(f"{r.get('d','')[5:]}:{r.get('p')}" for r in rq) + " | Binance " + (" ".join(f"{r.get('d','')[5:]}:{r.get('p')}" for r in bq) or "—") + " | 동등성 " + " ".join(f"{r.get('d','')[5:]}:{'OK' if str(r.get('ok'))=='1' else 'FAIL'}({r.get('m')})" for r in pq))
    # ⑤ 자원
    res = _q(hook, """SELECT round(quantile(0.95)(load1),2) AS load_p95, round(max(load1),2) AS load_max, argMax(toHour(ts + INTERVAL 9 HOUR), load1) AS load_max_h_kst,
                         max(mem_mysql_mb) AS mysql_mb, max(mem_ch_mb) AS ch_mb, max(mem_tm_mb) AS tm_mb, max(mem_kafka_mb) AS kafka_mb, max(mem_scheduler_mb) AS sched_mb,
                         argMax(disk_used_mb, ts) AS disk_used, argMax(disk_free_mb, ts) AS disk_free,
                         (argMax(disk_used_mb, ts) - argMin(disk_used_mb, ts)) / greatest(1, dateDiff('hour', min(ts), max(ts))) * 24 AS disk_mb_per_day,
                         dateDiff('hour', min(ts), max(ts)) AS span_h, count() AS samples
                      FROM cdc_pipeline.ops_metrics_5m WHERE ts >= now() - INTERVAL 7 DAY""")
    r = res[0] if res else {}
    if 'load_p95' in r:
        enough = int(r["span_h"]) >= 24
        days = (float(r["disk_free"]) / float(r["disk_mb_per_day"])) if enough and float(r["disk_mb_per_day"] or 0) > 0 else None
        disk_txt = (f"디스크 +{float(r['disk_mb_per_day']):.0f}MB/일, 남은 {int(days)}일" if days is not None else f"디스크 추세는 24h 뒤부터(표본 {r['samples']}개, {r['span_h']}h)")
        lines.append(f"*⑤ 자원({r['span_h']}h, 표본 {r['samples']})*  load1 p95 {r['load_p95']} max {r['load_max']}(KST {r['load_max_h_kst']}시) | 메모리 최대 MySQL {r['mysql_mb']}MiB CH {r['ch_mb']} TM {r['tm_mb']} Kafka {r['kafka_mb']} 스케줄러 {r['sched_mb']} | {disk_txt}")
        limits = {"MySQL": (float(r["mysql_mb"]), 1280), "ClickHouse": (float(r["ch_mb"]), 1792), "TaskManager": (float(r["tm_mb"]), 2304), "Kafka": (float(r["kafka_mb"]), 1280), "스케줄러": (float(r["sched_mb"]), 1024)}
        for k, (v, lim) in limits.items():
            if v > lim * 0.9: fixes.append(f"{k} 메모리 최대 {v:.0f}MiB = 한도 {lim} 의 {v/lim*100:.0f}%: 한도 상향 또는 프로세스 수 조정")
        if enough and float(r["load_p95"]) >= 3.5: fixes.append(f"load1 p95 {r['load_p95']} (코어 4): 피크 KST {r['load_max_h_kst']}시 — 수집 심볼 수·배치 크기 조정 검토(docs/31 §5)")
        if days is not None and days < 60: fixes.append(f"디스크 {int(days)}일 뒤 가득: TTL·토픽 보존 조정")
    else:
        lines.append("*⑤ 자원*  ops_metrics_5m 데이터 없음(수집기 확인)")
    # ⑥ 고칠 것
    lines.append("*⑥ 고칠 것*  " + (" / ".join(f"({i+1}) {f}" for i, f in enumerate(fixes)) if fixes else "없음 — 임계 안"))
    week = (datetime.utcnow() - timedelta(days=7)).strftime("%Y-%m-%d")
    body = "\n".join(lines)
    send_text_report(f"주간 운영 다이제스트 {week} ~ {datetime.utcnow().strftime('%Y-%m-%d')}", lines)
    try:
        import json
        hook.execute("INSERT INTO cdc_pipeline.ops_digest FORMAT JSONEachRow\n" + json.dumps({"week_start": week, "generated_at": datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S"), "body": body}, ensure_ascii=False))
    except Exception as e:  # noqa: BLE001
        context["ti"].log.warning("ops_digest insert failed: %s", e)
    context["ti"].log.info("digest:\n%s", body)
    return {"fixes": fixes, "lines": len(lines)}


with DAG(
    dag_id="weekly_digest",
    default_args=default_args,
    description="주간 운영 다이제스트: 트래픽 피크·지연·알럿·품질·자원·고칠 것 (docs/32)",
    schedule="0 0 * * 1",
    start_date=datetime(2026, 9, 20),
    catchup=False,
    tags=["ops", "digest"],
    max_active_runs=1,
) as dag:
    PythonOperator(task_id="build_digest", python_callable=_build)
