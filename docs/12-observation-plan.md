# 12. 7일 무변경 관찰 계획 (2026-09-09 ~ 09-16)

- 목적: 구조 변경(2·3차: Flink hashmap·TM 상향, producer 상한 제거, 전 코인 체결, 호가 경로 신설, ClickHouse 상향) 이후 **언제(시간대·요일) 지연·유실·중복·병목이 생기는지**를 7일 데이터로 확인하고, 그 근거로 다음 튜닝과 재생 증폭 실험의 기준선을 정한다.
- 원칙: 관찰 기간 중 파이프라인 구조·설정 변경 없음. 예외는 장애(체크포인트 실패 지속, 잡 FAILED, 디스크 90%, 알림 중단)뿐이며, 예외 조치도 worklog에 시각과 함께 기록한다.
- 수집: `scripts/observe/collect_metrics.sh` (crontab `*/5`, 87컬럼) → `~/pipeline-observation/metrics_5m.csv`. 보조: Airflow `health_check`(10분, 적재 지연 알림 포함), `daily_pipeline` dedup 게이트(매일 01:00 KST), 야간 프로브(09-09 13:00 UTC 1회).
- 시작 시점 코드 상태: git 태그 `obs-week1-start` (커밋 해시는 worklog에 기록).

## 1. 가설과 판정 기준 (관찰 전에 고정)

| # | 가설 | 지표(컬럼) | 임계값 | 근거(실측) |
|---|---|---|---|---|
| H1 | 체결 적재 지연은 피크(KST 09시, 22~24시)에도 p95 < 5초를 유지한다 (producer 상한 제거 효과) | `tr_lag_p50_s`, `tr_lag_p95_s`, `tr_lag_max_s` | p95 ≥ 5초가 5분 창 3연속이면 "포화 신호" | 이전 상한 10 rows/s에서 8월 37h 지연. dry-run(287마켓) p50 1.1~1.2s, p95 2.1s |
| H2 | producer 버퍼는 피크에도 두 자릿수 이하 | `p_buffer`, `p_warn5m` | buffer > 1,000 또는 WARNING 발생 | dry-run buffer 12~36 |
| H3 | 호가 e2e p95는 3초 미만 | `ob_e2e_p95_ms`, `ob_e2e_max_ms` | p95 ≥ 3,000ms 3연속 | 30분 실측 p95 1,892ms(JDBC 배치 2초 창) |
| H4 | 호가 수집기 재연결은 주 5회 미만, 발행 실패 0 | `c_reconnects`, `c_deliv_err` | 재연결 > 5/주 또는 deliv_err > 0 | 체결 producer 월 5회 재연결(전부 서버측) |
| H5 | 유실 없음: Kafka 오프셋 증가량 ≈ ClickHouse 적재 행수 | `k_trade_endoffset` 차분 vs `tr_rows5m`(create 비율 고려), `k_ob_endoffset` 차분 vs `ob_rows5m` | 일 단위 차이 > 1% | 3차 복원에서 trade_id 연속 확인 |
| H6 | 중복: 일일 dedup 게이트 건수가 수십 건 이하(sink 재시도분) | daily_pipeline Slack 리포트 | 하루 > 200건 | dedup 감사: 정상 운영 시 월 수십 건 |
| H7 | Flink 체크포인트 실패 0, 상태 크기 정체(수십 KB) | `fl_*_cp_fail`, `fl_*_state` | 실패 > 0 또는 state 단조 증가 | hashmap 전환 후 17.8KB(CDC)·61KB(호가) |
| H8 | ClickHouse 메모리 < 80% (1.4GiB), 파트 수 정체 | `ch_mem_bytes`, `ch_parts_*` | > 1.4GiB 3연속 또는 파트 수 지속 증가 | 상향 후 930MiB(52%) |
| H9 | Kafka 디스크: 호가 토픽 24h 상시 ≈ 18.5GB(RF2), 체결 토픽 ≤ 4GB/파티션 | `k_disk_b1_bytes` | 브로커1 로그 > 20GB | zstd 415 B/msg 실측 |
| H10 | 이상탐지 알림은 마켓별 편중이 있고 임계값 재조정이 필요할 것(287마켓) | `alerts5m` + `anomaly_alerts` 테이블 | 하루 > 2,000건 또는 상위 5마켓이 50% 이상 | 5코인 시 193건/일; dry-run 21분 5건 |
| H11 | MySQL 정리(10분×40K)가 유입을 따라간다 | `my_rows` | 행수 7일 후에도 단조 증가면 실패 | 유입 ≈ 400만/일 < 정리 용량 576만/일 |
| H12 | 10분 삭제 버스트가 Flink에 주는 비용은 작다 | `fl_cdc_e2e`(체크포인트 e2e), `tr_flink_lag_p95_s` 의 10분 주기 스파이크 | 스파이크 > 5초 | 2월 장애는 파서 버그가 원인(수정됨) |

## 2. 매일 확인(5분, 무변경)

- `metrics_5m.csv` 마지막 24h: H1·H3·H7·H8 임계 위반 여부.
- health_check 실패 여부, Slack 알림 건수.
- df, swap.

## 3. 7일 후 산출물

- `docs/13-observation-week1.md`: 시간대·요일별 지연 분포(p50/p95/max), 포화 시점, 알림 분포, 자원 추이, 가설별 판정, 튜닝 후보 순위.
- 관찰 데이터(CSV)와 분석 노트북은 실험 리포(별도)로 이동.

## 4. 관찰 뒤 후보(순서는 결과로 결정)

MySQL 파티션 만료(DROP PARTITION) 전환 · 이상탐지 임계값 마켓별 재조정 · ReplacingMergeTree 전환 + 2월 백필 · 재생 증폭 실험(브로커 장애 시나리오 포함) · 브로커 3→1 + KRaft · CDC 유의미화(A 가상 매매 원장, B 케이스 관리) · 허수성 호가 탐지(호가 diff × 체결 대조).
