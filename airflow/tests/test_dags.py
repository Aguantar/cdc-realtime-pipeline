"""DAG 구조 검증 테스트.

DAG import 에러, 순환 의존성, 태스크 누락 등을 검증합니다.
Pipeline-as-Code 철학: DAG도 코드이므로 테스트합니다.
"""

import os
import sys

import pytest

# plugins 경로를 Python path에 추가 (커스텀 오퍼레이터 import 지원)
# 컨테이너 내부(/opt/airflow)와 호스트(airflow/) 모두 지원
_test_dir = os.path.dirname(os.path.abspath(__file__))
AIRFLOW_HOME = os.environ.get("AIRFLOW_HOME", os.path.dirname(_test_dir))
sys.path.insert(0, os.path.join(AIRFLOW_HOME, "plugins"))

os.environ.setdefault("AIRFLOW_HOME", AIRFLOW_HOME)
os.environ.setdefault("AIRFLOW__CORE__LOAD_EXAMPLES", "false")
os.environ.setdefault(
    "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN", "sqlite:////:memory:"
)

from airflow.models import DagBag


@pytest.fixture(scope="session")
def dag_bag():
    dag_dir = os.path.join(AIRFLOW_HOME, "dags")
    return DagBag(dag_folder=dag_dir, include_examples=False)


def test_no_import_errors(dag_bag):
    """모든 DAG 파일이 import 에러 없이 로드되는지 확인."""
    assert len(dag_bag.import_errors) == 0, (
        f"DAG import errors: {dag_bag.import_errors}"
    )


def test_expected_dags_loaded(dag_bag):
    """필수 DAG들이 로드되었는지 확인."""
    expected_dags = {"health_check", "daily_pipeline"}
    loaded_dags = set(dag_bag.dag_ids)
    missing = expected_dags - loaded_dags
    assert not missing, f"Missing DAGs: {missing}"


def test_health_check_dag_structure(dag_bag):
    """health_check DAG의 태스크 구조 검증."""
    dag = dag_bag.get_dag("health_check")
    assert dag is not None

    expected_tasks = {
        "check_clickhouse_ingest",
        "check_flink_jobs",
        "check_kafka_health",
        "check_producer_activity",
        "check_kafka_connect",
        "evaluate_health",
    }
    actual_tasks = {t.task_id for t in dag.tasks}
    assert expected_tasks == actual_tasks, (
        f"Expected: {expected_tasks}, Got: {actual_tasks}"
    )

    # evaluate_health는 5개 체크 태스크에 의존해야 함
    evaluate = dag.get_task("evaluate_health")
    upstream_ids = {t.task_id for t in evaluate.upstream_list}
    assert upstream_ids == {
        "check_clickhouse_ingest",
        "check_flink_jobs",
        "check_kafka_health",
        "check_producer_activity",
        "check_kafka_connect",
    }


def test_daily_pipeline_dag_structure(dag_bag):
    """daily_pipeline DAG의 태스크 구조 검증."""
    dag = dag_bag.get_dag("daily_pipeline")
    assert dag is not None

    expected_tasks = {
        "dbt_run",
        "dbt_test",
        "get_coin_list",
        "validate_coin",
        "quality_gate",
        "check_duplicates",
        "generate_report",
        "slack_daily_report",
    }
    actual_tasks = {t.task_id for t in dag.tasks}
    assert expected_tasks == actual_tasks, (
        f"Expected: {expected_tasks}, Got: {actual_tasks}"
    )


def test_daily_pipeline_dependencies(dag_bag):
    """daily_pipeline DAG의 의존성 체인 검증."""
    dag = dag_bag.get_dag("daily_pipeline")

    # dbt_run → dbt_test
    dbt_test = dag.get_task("dbt_test")
    assert "dbt_run" in {t.task_id for t in dbt_test.upstream_list}

    # dbt_test → get_coin_list
    get_coins = dag.get_task("get_coin_list")
    assert "dbt_test" in {t.task_id for t in get_coins.upstream_list}

    # quality_gate → generate_report
    gen_report = dag.get_task("generate_report")
    assert "quality_gate" in {t.task_id for t in gen_report.upstream_list}

    # slack_daily_report는 generate_report와 check_duplicates에 의존
    slack = dag.get_task("slack_daily_report")
    upstream_ids = {t.task_id for t in slack.upstream_list}
    assert "generate_report" in upstream_ids
    assert "check_duplicates" in upstream_ids


def test_no_cycles(dag_bag):
    """모든 DAG에 순환 의존성이 없는지 확인."""
    for dag_id, dag in dag_bag.dags.items():
        # DagBag 로드 시 순환 감지되면 import_errors에 포함됨
        assert dag is not None, f"DAG {dag_id} is None"


def test_daily_pipeline_catchup_disabled(dag_bag):
    """daily_pipeline은 catchup=False여야 함 (과거 실행 방지)."""
    dag = dag_bag.get_dag("daily_pipeline")
    assert dag.catchup is False


def test_health_check_max_active_runs(dag_bag):
    """health_check는 동시 실행 1개로 제한."""
    dag = dag_bag.get_dag("health_check")
    assert dag.max_active_runs == 1


def test_daily_pipeline_has_sla(dag_bag):
    """daily_pipeline의 dbt_run에 SLA가 설정되어 있는지 확인."""
    dag = dag_bag.get_dag("daily_pipeline")
    dbt_run = dag.get_task("dbt_run")
    assert dbt_run.sla is not None, "dbt_run should have SLA configured"
