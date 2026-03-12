"""Slack 알림 콜백 모듈.

DAG/태스크의 성공/실패 시 Slack Webhook으로 컨텍스트 포함 알림을 전송합니다.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any

import requests
from airflow.models import Variable

logger = logging.getLogger(__name__)


def _get_webhook_url() -> str | None:
    """Airflow Variable에서 Slack Webhook URL을 가져옵니다."""
    try:
        return Variable.get("slack_webhook_url", default_var=None)
    except Exception:
        return None


def _send_slack_message(payload: dict[str, Any]) -> None:
    """Slack Webhook으로 메시지를 전송합니다."""
    webhook_url = _get_webhook_url()
    if not webhook_url:
        logger.warning("Slack webhook URL not configured (Variable: slack_webhook_url)")
        return

    try:
        response = requests.post(
            webhook_url,
            data=json.dumps(payload),
            headers={"Content-Type": "application/json"},
            timeout=10,
        )
        response.raise_for_status()
    except requests.RequestException as e:
        logger.error("Failed to send Slack notification: %s", e)


def task_failure_callback(context: dict[str, Any]) -> None:
    """태스크 실패 시 컨텍스트 포함 Slack 알림."""
    task_instance = context.get("task_instance")
    dag_id = context.get("dag").dag_id if context.get("dag") else "unknown"
    task_id = task_instance.task_id if task_instance else "unknown"
    execution_date = context.get("execution_date", datetime.now())
    exception = context.get("exception", "No exception info")
    log_url = task_instance.log_url if task_instance else ""

    payload = {
        "blocks": [
            {
                "type": "header",
                "text": {
                    "type": "plain_text",
                    "text": f"Task Failed: {dag_id}.{task_id}",
                },
            },
            {
                "type": "section",
                "fields": [
                    {"type": "mrkdwn", "text": f"*DAG:*\n{dag_id}"},
                    {"type": "mrkdwn", "text": f"*Task:*\n{task_id}"},
                    {
                        "type": "mrkdwn",
                        "text": f"*Execution Date:*\n{execution_date}",
                    },
                    {
                        "type": "mrkdwn",
                        "text": f"*Error:*\n```{str(exception)[:300]}```",
                    },
                ],
            },
            {
                "type": "actions",
                "elements": [
                    {
                        "type": "button",
                        "text": {"type": "plain_text", "text": "View Log"},
                        "url": log_url,
                    }
                ],
            },
        ]
    }
    _send_slack_message(payload)


def task_success_callback(context: dict[str, Any]) -> None:
    """태스크 성공 시 Slack 알림 (DAG-level에서만 사용 권장)."""
    dag_id = context.get("dag").dag_id if context.get("dag") else "unknown"
    execution_date = context.get("execution_date", datetime.now())

    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": f"*DAG Completed:* `{dag_id}` | {execution_date}",
                },
            }
        ]
    }
    _send_slack_message(payload)


def sla_miss_callback(dag, task_list, blocking_task_list, slas, blocking_tis) -> None:
    """SLA miss 시 Slack 알림."""
    task_names = [t.task_id for t in task_list]
    payload = {
        "blocks": [
            {
                "type": "header",
                "text": {
                    "type": "plain_text",
                    "text": "SLA Miss Alert",
                },
            },
            {
                "type": "section",
                "fields": [
                    {"type": "mrkdwn", "text": f"*DAG:*\n{dag.dag_id}"},
                    {
                        "type": "mrkdwn",
                        "text": f"*Tasks:*\n{', '.join(task_names)}",
                    },
                ],
            },
        ]
    }
    _send_slack_message(payload)


def send_health_alert(unhealthy_components: list[dict[str, Any]]) -> None:
    """헬스체크 이상 발견 시 Slack 알림."""
    fields = []
    for comp in unhealthy_components:
        fields.append(
            {
                "type": "mrkdwn",
                "text": f"*{comp['name']}:*\n{comp['message']}",
            }
        )

    payload = {
        "blocks": [
            {
                "type": "header",
                "text": {
                    "type": "plain_text",
                    "text": "Pipeline Health Alert",
                },
            },
            {"type": "section", "fields": fields[:10]},
        ]
    }
    _send_slack_message(payload)


def send_daily_report(report_data: dict[str, Any]) -> None:
    """일일 리포트 Slack 전송."""
    date = report_data.get("date", "N/A")
    quality = report_data.get("quality", {})
    summary = report_data.get("summary", [])
    volume_spikes = report_data.get("volume_spikes", [])
    duplicates = report_data.get("duplicates_found", 0)
    latency = report_data.get("latency_stats", {})
    anomaly_counts = report_data.get("anomaly_counts", {})
    execution_sec = report_data.get("execution_seconds", "?")

    # Quality 상태 이모지
    pass_rate = quality.get("pass_rate", 0)
    if pass_rate == 100:
        q_emoji = ":large_green_circle:"
    elif pass_rate >= 80:
        q_emoji = ":large_yellow_circle:"
    else:
        q_emoji = ":red_circle:"

    blocks = [
        {
            "type": "header",
            "text": {
                "type": "plain_text",
                "text": f"CDC Daily Report — {date}",
            },
        },
        # 파이프라인 실행 요약
        {
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": (
                    f"{q_emoji} *Pipeline Summary*\n"
                    f"실행 시간: {execution_sec}s | "
                    f"코인: {quality.get('total', 0)}개 | "
                    f"품질 통과: {quality.get('passed', 0)}/{quality.get('total', 0)} "
                    f"({pass_rate}%)"
                ),
            },
        },
        {"type": "divider"},
    ]

    # 실패 코인 상세 (있을 때만)
    failed_coins = quality.get("failed_coins", [])
    if failed_coins:
        fail_lines = []
        for fc in failed_coins[:5]:
            issues = ", ".join(fc.get("issues", []))
            fail_lines.append(f"• {fc['coin']}: {issues}")
        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": ":warning: *Quality Issues*\n" + "\n".join(fail_lines),
            },
        })

    # CDC 지연 통계
    if latency:
        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": (
                    ":stopwatch: *CDC Latency*\n"
                    f"p50: {latency.get('p50', '?')}ms | "
                    f"p95: {latency.get('p95', '?')}ms | "
                    f"p99: {latency.get('p99', '?')}ms | "
                    f"max: {latency.get('max', '?')}ms"
                ),
            },
        })

    # Top 코인 (전일 대비 변화 포함)
    if summary:
        coin_lines = []
        for row in summary[:5]:
            market = row.get("market", "?")
            trades = row.get("trade_count", "?")
            close = row.get("close_price", "?")
            vol_chg = row.get("volume_change_pct", None)
            price_chg = row.get("close_change_pct", None)

            chg_parts = []
            if price_chg is not None and price_chg != 0:
                sign = "+" if float(price_chg) > 0 else ""
                chg_parts.append(f"가격 {sign}{price_chg}%")
            if vol_chg is not None and vol_chg != 0:
                sign = "+" if float(vol_chg) > 0 else ""
                chg_parts.append(f"거래량 {sign}{vol_chg}%")

            chg_text = f" ({', '.join(chg_parts)})" if chg_parts else ""
            coin_lines.append(
                f"  {market}: {trades:,} trades, ₩{close:,}{chg_text}"
                if isinstance(trades, int) and isinstance(close, (int, float))
                else f"  {market}: {trades} trades, ₩{close}{chg_text}"
            )

        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": ":chart_with_upwards_trend: *Top Coins*\n```\n"
                + "\n".join(coin_lines) + "\n```",
            },
        })

    # 이상 탐지 요약
    if anomaly_counts:
        total_anomalies = sum(anomaly_counts.values())
        if total_anomalies > 0:
            anomaly_parts = [f"{k}: {v}" for k, v in anomaly_counts.items() if v > 0]
            blocks.append({
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": (
                        f":rotating_light: *Anomaly Alerts* ({total_anomalies}건)\n"
                        + " | ".join(anomaly_parts)
                    ),
                },
            })

    # 볼륨 스파이크
    if volume_spikes:
        spike_lines = [
            f"• {s.get('market', '?')} {s.get('hour_start', '?')} (x{s.get('volume_ratio', '?')})"
            for s in volume_spikes[:3]
        ]
        blocks.append({
            "type": "section",
            "text": {
                "type": "mrkdwn",
                "text": ":zap: *Volume Spikes*\n" + "\n".join(spike_lines),
            },
        })

    # 중복 + 푸터
    footer_parts = []
    if duplicates > 0:
        footer_parts.append(f":warning: 중복 trade_id: {duplicates}건")
    else:
        footer_parts.append(":white_check_mark: 중복 없음")

    blocks.append({
        "type": "context",
        "elements": [
            {"type": "mrkdwn", "text": " | ".join(footer_parts)}
        ],
    })

    _send_slack_message({"blocks": blocks})
