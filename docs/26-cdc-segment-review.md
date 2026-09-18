# 26. CDC 구간 재검토 — producer → MySQL → Debezium → Kafka (2026-09-18, 결정 대기)

docs/19 §2-1 순서 1. 대상은 docs/19 #1(체결 Kafka 선기록), #4(`markets` 마스터), 그리고 부하 실험·정지 실험에서 드러난 MySQL 정리 방식. 전부 실측에서 출발한다.

## 1. 지금 구조가 실제로 어떻게 돌고 있나 (실측, 09-18)
| 항목 | 값 |
|---|---|
| MySQL 원장 | `crypto_trades` 16.1M행(7일 보존), 데이터 2.2GB + 인덱스 1.7GB, PK trade_id, UNIQUE (market, sequential_id) — INSERT IGNORE 중복 흡수의 근거. 파티션 없음 |
| producer 쓰기 | 배치 20행 / 2초 flush, INSERT IGNORE |
| 정리 | MySQL EVENT `cleanup_old_trades`: 10분마다 `DELETE … WHERE created_at < NOW()-7d LIMIT 40000`. **실행 계획 = 풀스캔(type ALL, 15.9M행)** — created_at 단독 인덱스가 없다. 실측 59초 실행, MySQL CPU 51%(docs/23 §5-1 정체 시각과 일치) |
| Debezium | snapshot.mode initial, `skipped.operations` 없음, binlog_row_image FULL → **삭제도 전체 행 이미지로 Kafka 에 간다** |
| 토픽 구성 | 24h 체결 토픽 4,109,009 메시지 중 ClickHouse 적재(create) 2,235,132 → **삭제 이벤트 ≈ 1,873,877 = 46%**. Flink 파서가 op='d' 를 버린다(docs/20). 즉 Kafka 쓰기·Debezium 처리·Flink 파싱의 절반이 버릴 데이터 |
| binlog | 15파일 1.38GB, MySQL 볼륨 5.8GB. 처음엔 `binlog_expire_logs_seconds=0` 만 보고 **무기한이라고 잘못 판단** → 적용 단계에서 compose 의 레거시 옵션 `--expire-logs-days=3` 이 살아 있음을 발견(둘은 동시에 못 씀, 오류 4113). 실제 보존은 **3일**이었다. 정정 |
| 원장의 실제 성능 | 브로커 정지 4회(1대 5분·전체 3분·전체 1분·재기동 14초)에서 체결 유실 0 — 원장이 4/4 지켰다(docs/23 §3·§7·§7-1, docs/24 §4-4) |

## 1-1. 실무는 어떻게 하나 (사용자 질문: 삭제 이벤트는 실무에서도 필요 없는가, 우리 스펙 때문인가)
- **삭제 이벤트가 필요한지는 스펙이 아니라 의미로 갈린다.** 받는 쪽이 원본의 거울(mirror)이면 삭제는 데이터 자체라 필수다(회원 탈퇴가 복제본에도 반영돼야). 받는 쪽이 역사 보관소(archive)면 원본의 보존 정리 삭제는 유지보수 동작이라 전달하면 역사가 지워진다. 우리 MySQL(7일 완충)→ClickHouse(365일)는 후자. 스펙은 "그 낭비가 얼마나 아픈가"만 정한다(넉넉한 서버면 비효율, 4코어 동거면 정체 원인).
- **실무는 업무 삭제와 보존 삭제를 처음부터 구분한다**: ① 원본 파티션 DROP(행 이벤트가 binlog 에 안 생김, 정석 — 우리는 PK·유니크 키에 날짜가 없어 못 함 = 설계 부채) ② 정리 세션만 `sql_log_bin=0`(원본이 "복제 대상 아님" 선언, 다른 복제본이 없을 때) ③ 커넥터 `skipped.operations=d`(소비자가 "이 테이블엔 업무 삭제가 없다" 선언, 되돌리기 쉬움 — 체결은 불변 사건이라 성립).
- **OLTP 삭제의 불문율 셋**: 인덱스로 찾을 것, 작게 나눌 것, 운영 트래픽과 다투지 않을 것. 우리는 둘째만 지켰다. 실무의 DB 는 자기 서버가 있어 풀스캔이 "낭비"에 그치지만 우리는 "정체"가 된다 → 인덱스가 실무에선 습관, 우리에겐 필수. 정석 도구는 pt-archiver 류(작은 배치 + 휴식 + 인덱스 순서).
- **binlog 보존** = 가장 느린 소비자의 지연 + 복구 시간보다 길게(보통 며칠~몇 주, 백업 정책과 묶음). 우리는 3일이었고(compose 레거시 옵션), 소비자가 Debezium 하나·원장 7일 → 30일이면 재스냅샷까지 피한다.
- **ClickHouse 의 자리**: 삭제 이벤트를 본 적이 없다(Flink 가 버림) → 1번을 고쳐도 데이터 불변, 부하만 준다. ClickHouse 의 보존(TTL 로 파트 통째 만료)은 이미 정석 — MySQL 이 못 하는 파티션 DROP 을 ClickHouse 는 하고 있다. 언젠가 삭제를 전달해야 하면 행을 지우는 게 아니라 "지워졌다" 행을 넣는다(ReplacingMergeTree is_deleted / CollapsingMergeTree) — mutation 이 비싸서 실무의 CDC 거울도 그렇게 한다.

## 2. #1 "체결도 Kafka 에 선기록" — 철회
- 원래 근거(docs/19·docs/20 §5): 동기 MySQL 쓰기가 asyncio 루프를 막아 keepalive 가 멈췄다 → 비동기 Kafka 프로듀서면 루프가 안 막힌다.
- 이후 실측: 루프 막힘은 TCP 가 흡수해 유실이 없었고, 브로커가 없는 상황에서는 **MySQL 이 있었기 때문에** 체결이 살았다. Kafka 를 앞에 두면 그 안전망이 사라진다(호가가 정확히 그 구조였고 3분 정지에 35,838 유실).
- 결론: 원장이 MySQL 인 동안 #1 은 하지 않는다. 남는 문제(루프 막힘)는 producer 의 MySQL 쓰기를 별도 스레드/비동기 드라이버로 옮기는 것으로 푼다 — 구조 변경이 아니라 구현 변경. docs/19 #1 을 "철회(근거: 정지 실험 4회)"로.

## 3. 정리 방식 — DELETE 를 어떻게 할 것인가
| 선택지 | 내용 | 판정 |
|---|---|---|
| A. 파티션 DROP | 일 파티션으로 나눠 DROP → binlog 에 행 이벤트 없음, 즉시 | **정정(09-18 11:30)**: created_at 으로 나누면 유니크 키에 created_at 이 들어가 gap-fill 재삽입이 중복이 되지만, **upbit_timestamp(체결 시각)** 으로 나누면 같은 체결은 언제 넣어도 키가 같아 중복 방지가 유지된다(sequential_id 가 이미 체결 시각을 품음). 따라서 **근본 해결이 맞다** — 다만 원장 교체 절차(새 테이블·`sql_log_bin=0` 복사·AUTO_INCREMENT 연속·원자적 RENAME·Debezium 스키마·Flink 파서 확인)가 크고, 2층 원장 설계가 MySQL 스키마를 다시 건드릴 가능성이 커 **2층과 묶어 한 번에**. 그때까지는 B·C·D 로 응급 처치 |
| B. Debezium `skipped.operations=d` | 삭제 이벤트를 소스에서 버린다 | **채택 제안**. 토픽·Debezium·Flink 부하 −46%, binlog 는 그대로. Flink 는 이미 버리고 있어 결과 불변. MV `mv_latency_stats` 는 op in (c,u,d) 를 세므로 event_count 가 create 만으로 바뀜(대시보드 주석) |
| C. `created_at` 단독 인덱스 | 온라인 DDL(InnoDB), 16M행 | **채택 제안**. DELETE 가 풀스캔 → 인덱스 범위. 59초·CPU 51% 의 정체 유발원 제거. 인덱스 유지 비용은 쓰기당 항목 1개 |
| D. binlog 보존 30일 | 레거시 `expire_logs_days` 를 0 으로 내리고 `binlog_expire_logs_seconds=2592000` (SET PERSIST) + compose 옵션 교체 | **채택**. 실제 보존은 3일이었다(위 정정). 3일이면 Debezium 이 3일 넘게 멈춘 뒤엔 재스냅샷이 필요하고(원장 7일이라 유실은 없음), 30일은 그 재스냅샷조차 피하는 여유. 디스크 약 14GB(1.38GB/3일 비례), 344GB 여유 |
| E. DELETE 주기·LIMIT 조정 | 10분/4만 → 그대로 | C 뒤엔 문제가 아니다. 실측 후 판단 |

B·C·D 는 정지 없이 적용 가능(B 는 커넥터 설정 PUT → 커넥터가 자체 재시작, 수 초). A(체결 시각 파티션) 가 들어오면 B·C 는 불필요해지고 D 만 남는다. 적용 뒤 검증: 24h 토픽 메시지 ≈ ClickHouse 적재 행(삭제 0), DELETE 실행 시간(processlist·`Innodb_rows_deleted` 증가 속도), health_check 의 insert 지연 알림 없음.

## 4. #4 `markets` 마스터 — `dim_markets`
- 왜 필요한가: ① 거래소 예외를 흉내 내려면 상장일이 필요(신규 상장 96시간 제외, docs/16) ② 대조 분모·커버리지 체크·규칙 평가가 각자 거래소 목록을 부르고 있다 → 한 곳으로 ③ BFC 사고(상장 후 6일 무수집)를 "우리가 처음 본 날 − 상장일" 로 표에 남길 수 있다.
- 재료(실측): `/v1/market/all?is_details=true`(현재 목록·경보 플래그·한/영 이름), 일봉 200일 창에서 마켓별 첫 봉 = 상장일 근사(287 중 55 마켓이 창 안, 나머지는 "≤ 2026-03-01"), 우리 `crypto_trades` 의 마켓별 첫 체결.
- 형태: dbt 테이블 `dim_markets`(market, names, listing_date_est, listing_date_bound, first_seen_ours, seen_gap_days, warning flags, is_active, updated_at), 일 1회 갱신(일봉 fetch 와 같은 DAG). 소비: `int_reconcile_hourly` 분모, `dq_rule_eval_daily` 의 96h 제외, health_check 커버리지의 "신규 상장인데 우리 체결 없음" 사유 표기.
- 하지 않는 것: 상장 공지 파싱(비정형·근거 약함). 상장일은 일봉 첫 날로 정의하고 그 한계(창 200일)를 컬럼에 남긴다.

## 5. 결정 요청과 순서
1. B·C·D 적용(정지 없음, 30분) → 24h 검증 표 → docs/26 §6.
2. `dim_markets` 구현(dbt + fetch 확장, 반나절) → 소비처 3곳 교체.
3. producer MySQL 쓰기 비동기화는 별도(루프 막힘은 실측상 유실을 만들지 않았으므로 우선순위 낮음).

## 6. 실무라면 이 프로젝트를 어떻게 놓겠나 (사용자 질문, 09-18)
- **시세는 스트리밍 + 대조, 원장은 CDC.** CDC 의 자리는 회사가 소유한 트랜잭션 DB(주문·계정·원장)다. 시세 피드는 MySQL 에 넣지 않고 WS → 스풀 → Kafka 로 가고, 원본은 거래소 REST 이력이라 대조 + 백필로 메운다(우리 대조 DAG·gap-fill 이 그 핵심). 2층의 가상 매매 원장이 CDC 를 제자리에 놓는 작업이고, 이 문서의 삭제 이벤트 문제는 시세 테이블을 CDC 로 흘려서 생긴 부작용이다.
- **호가는 내구 우선 + 계층 보존.** 첫 복사본을 스풀이나 여러 서버의 브로커로 지키고, 원본은 며칠·집계는 길게.
- **SLO 는 소비자가 정한다.** p95 4.7s·대조 100%·중복 0 이 "누구와 약속한 값"이 된다. 소비자 부재가 현재 가장 큰 공백.
- **환경 분리·스키마 계약.** 동일 사양 스테이징, 서버·AZ 분산 브로커, 관리형/클러스터 ClickHouse, 시크릿 매니저, 스키마 레지스트리(Avro/Protobuf) + dbt 계약.
- **그대로 가져갈 것**: 측정 먼저(대조·장애 주입·정답 역산·근거로 고친 알림), 런북·사후 기록·섀도. DE 는 신호를 만들지 않고 신호가 설 자리(결합 마트·라벨·평가 하네스·케이스 테이블)를 만든다.
- docs/26 §3 의 제안 3건은 그 방향으로 가는 동안 시세 CDC 의 부작용을 줄이는 응급 처치다.
