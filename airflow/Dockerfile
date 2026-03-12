FROM apache/airflow:2.8.1-python3.10

USER airflow

RUN pip install --no-cache-dir \
    dbt-core==1.7.9 \
    dbt-clickhouse==1.7.2 \
    statsd>=3.3
