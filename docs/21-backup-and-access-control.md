# 21. 백업·복구 리허설·접근 통제 (2026-09-17 00:25 ~ 00:50 UTC)

docs/19 #13(저장소 내구성)과 #15(접근 통제)를 한 작업으로 닫았다. 둘 다 ClickHouse 재시작이 필요해 정지 창을 한 번만 쓰기 위해 묶었다.
원칙은 같다. 각 결정에 "왜"와 실측을 붙이고, 처음 설계가 실측으로 바뀐 곳은 그대로 남긴다.

## 1. 백업 — "백업이 있다"는 복구 리허설 뒤에만 말한다

| 항목 | 결정 | 왜 |
|---|---|---|
| 대상 | `cdc_pipeline` 전체에서 `orderbook_raw` 제외 | 호가 원본은 7일 TTL 이고 Parquet 롤링(§4)이 따로 담당. 나머지는 영구 보존 대상 |
| 방식 | ClickHouse 네이티브 `BACKUP DATABASE … TO File()` | 파트 파일 단위 복사라 서버 부하가 거의 없고(3.9GiB 20초), 증분(`base_backup`)을 지원 |
| 위치 | 호스트 `~/clickhouse-backups` → rsync → Oracle `/mnt/backup/clickhouse` | 다른 호스트·다른 디스크에 있어야 백업이다. 미니PC NVMe 한 장에만 있던 것이 docs/17 의 가장 큰 구멍이었다 |
| Oracle 볼륨 | 블록 볼륨 150GB 신규(`cdc-backup-150g`), `/mnt/backup`, fstab `nofail` | Always Free 200GB 중 부트 47GB 만 쓰고 있었다(실측: 볼륨 목록). 150GB 는 6개월 Parquet 롤링(~100GB) + ClickHouse 백업(~20GB)을 담는다 |
| 설정 반영 | `backups.allowed_path` 는 **재시작 없이 hot-reload 됨**(실측: config.d 에 파일 넣고 8초 뒤 BACKUP 성공) | 첫 백업과 리허설을 재시작 전에 끝낼 수 있었다. 영구 반영은 compose 마운트로 재시작 때 |

### 실측
| 단계 | 값 |
|---|---|
| 전체 백업 | 3.90 GiB, 4,876 파일, **20초** (00:28:19 → 00:28:39) |
| 컨테이너 → 호스트 추출 | 25초 |
| rsync 미니PC → Oracle (WireGuard) | 4.19 GB, **71초** (~59 MB/s) |
| **복원 리허설** (Oracle, 임시 ClickHouse arm64 컨테이너 3GB 메모리) | `RESTORE DATABASE` **79초**, crypto_trades 109,829,325행 복원. 원본의 백업 시각 근사치(109,829,415)와 90행 차이 = 백업 순간 유입분. 복원본에서 실제 집계 쿼리 성공 |
| 첫 시도 실패 | rsync 가 미니PC 소유자(uid 1000)를 그대로 옮겨 컨테이너의 clickhouse(uid 101)가 못 읽음 → `chmod a+rX` 후 성공. 리허설 없이는 몰랐을 종류의 실패 |

## 2. 접근 통제 — 설계가 실측으로 바뀐 곳

### 처음 설계
`default` 사용자를 비밀번호 없이 두되 `<networks>` 로 127.0.0.1/::1 만 허용하고, 네트워크 클라이언트는 전용 사용자를 쓴다.

### 무엇이 틀렸나 — 두 번 틀렸다
**첫 번째 판단(틀림)**: n8n 접속이 ClickHouse 에 `127.0.0.1` 로 보인다고 읽고 "Docker userland proxy 가 출발지를 가린다, 따라서 IP 제한은 무의미하다"고 결론 내렸다.
이후 서버 Access 로그를 제대로 보니 n8n 접속은 **`from: 10.88.0.1`** 로 기록돼 있었다. 앞서 본 127.0.0.1 은 내 로컬 curl 이었다.
Linux 의 Docker 는 published port 를 iptables DNAT 로 처리해 출발지 IP 를 보존한다. 즉 **IP 제한은 유효하다**. 이 판단 오류로 `n8n_reader` 의 IP 제한을 한 번 풀었다가 다시 걸었다.

**두 번째 판단(틀림)**: "n8n HTTP 노드가 URL 의 user/password 파라미터를 보내지 않는다"고 추정했다.
실제로는 **n8n 이 실행하는 것은 `workflow_history` 의 발행 버전(`activeVersionId`)** 이고, 내가 SQL 로 고친 `workflow_entity.nodes` 는 초안이었다.
URL 파라미터·헤더·자격증명 연결을 초안에 세 번 고쳐도 실행에는 한 번도 반영되지 않았다. 00:38 의 "성공"은 그 시점에 `default` 가 아직 무인증이라 통과한 것이었다.
발행 버전에 같은 변경을 적용하자 즉시 성공했다(00:59:27). 교훈: **시스템이 어디서 설정을 읽는지 먼저 확인하고 고친다.** 초안 편집 3회는 전부 헛일이었고, n8n 재시작을 5번 했다.

그래도 남는 사실 하나: `default` 에 비밀번호를 거는 것이 IP 제한보다 강한 통제라는 점은 변하지 않는다. 확정 설계는 둘 다 쓴다.

### 확정 설계
| 사용자 | 용도 | 권한 | 인증 |
|---|---|---|---|
| `default` | 컨테이너 안 `clickhouse-client`(운영 스크립트 전부가 `docker exec` 경유), healthcheck | 전체 | **비밀번호**. 클라이언트 설정 `/etc/clickhouse-client/config.xml` 을 호스트 파일(600, 저장소 밖)에서 마운트 → 스크립트 수정 0 |
| `pipeline` | Flink 싱크·Airflow·dbt·Grafana·producer 계보 | `cdc_pipeline.*`, `circuit_connect.*` RW | env 에서 주입 |
| `readonly_user` | 호스트 대조·관찰 스크립트 (기존) | SELECT | 기존 |
| `n8n_reader` | Oracle n8n 두 워크플로우 | SELECT (`cdc_pipeline`, `circuit_connect`), readonly=2, **HOST IP 10.88.0.1**(터널 피어) | n8n 자격증명(httpBasicAuth, `n8n import:credentials` 로 암호화 저장). 워크플로우 JSON 에 평문 없음(검증) |
| `icepush` | 타 프로젝트 icepush-api·icepush dbt DAG | `icepush.*`, `icepush_dbt.*` RW | env |

포트 바인딩은 `0.0.0.0:8123` 을 유지한다. 127.0.0.1 로 묶으면 n8n 경로(wg0 IP)가 필요한데, wg0 이 docker 보다 늦게 뜨는 재부팅에서 컨테이너 기동이 실패할 위험이 있어 보류했다. 통제는 **인증(전 사용자 비밀번호) + 사용자별 IP 제한(가능한 곳)** 이고, 무인증 접속이 실제로 막힘을 컷오버에서 확인했다(HTTP 403).

### 영향 범위 점검에서 걸린 것
이 변경 전에 "누가 default 로 붙는가"를 query_log 로 세었다. 이 파이프라인 밖의 두 프로젝트가 걸렸다.
- **circuit 잡**(타 프로젝트 Flink 잡): `CLICKHOUSE_URL` env 를 읽는데 새 JobManager env 가 `cdc_pipeline` 을 가리킨다 → 제출 시 자기 DB URL 을 env 로 따로 준다(런북에 반영).
- **icepush-api·icepush dbt DAG**: 인증 없이 default. 둘 다 env 로 사용자·비밀번호를 받을 수 있게 되어 있어 전용 사용자 `icepush` 를 만들고 env 만 주입했다(icepush 저장소 2파일 커밋 1699e50).
점검하지 않았으면 비밀번호를 거는 순간 게임 API 와 타 프로젝트 잡이 죽었다.

## 3. 컷오버 (런북 `scripts/ops/clickhouse-auth-cutover.sh`)
한 정지 창에 묶은 이유: ClickHouse 재시작 중 Flink JDBC 싱크가 실패→재시도하면 at-least-once 중복이 생긴다(docs/07). 3 잡을 savepoint 로 세운 뒤 재시작하고 복원한다.

| 단계 | 결과 |
|---|---|
| 3 잡 savepoint 정지 | 00:43:xx |
| clickhouse·flink JM/TM·grafana·airflow×2·producer 재생성 | 00:44:17 준비 |
| 무인증 default | **HTTP 403** |
| 3 잡 복원 | 전부 RUNNING, CDC 정지 전 max trade_id 114,050,382 → 첫 행 **114,050,383 (+1)** |
| producer 기동 gap-fill | 재생성 공백(~65초) 원장 714 / 삽입 **565**, 가드 `lateEventsSkipped` = **565** (정확히 일치) |
| Grafana 데이터소스 | "Data source is working" (pipeline) |
| dbt debug / health_check / daily_pipeline 태스크 | OK (pipeline) |
| icepush-api | health 200 |
| circuit 잡 | 자기 DB(`circuit_connect`)에 계속 적재 |
| n8n | 발행 버전 수정 후 **success** (00:59:27), ClickHouse 에 `n8n_reader` 로 도착 확인 |

## 4. 자동화 — `backup_daily` DAG (01:20 UTC)

| 태스크 | 하는 일 | 왜 이렇게 |
|---|---|---|
| clickhouse_backup | 매월 1일 전체, 그 외 최신 전체를 base 로 증분. 이름 = 실행일 | 증분은 base 이후 바뀐 파트만 복사(첫 증분 80MB vs 전체 3.9GB). 이름이 실행일인 이유: 백업은 "그 시점의 상태" |
| export_orderbook_parquet | 전날(ds) `orderbook_raw` 파티션 → Parquet zstd, 행그룹 20만, 메모리 상한 800MB. PAR1 매직·크기 검사 | 7일 TTL 로 지워지는 유일한 장기본. 이름이 데이터 날짜인 이유: Parquet 은 "그 날의 데이터" |
| sync_to_oracle | rsync -a --partial --chmod=ugo+rX → `/mnt/backup/clickhouse` | 다른 호스트·디스크. `--chmod` 는 리허설 첫 실패(750 권한)의 재발 방지 |
| apply_remote_retention | Parquet 120일, 전체 3세대, 그보다 오래된 증분 삭제 | 아래 용량 산정 |
| prune_local | 스테이징: Parquet 3일, 증분 14일, 전체 최신 1 | 로컬은 스테이징이지 백업이 아니다 |
| verify_remote_in_sync | `rsync -n` 으로 전송 대기 파일 0 확인 | "보냈다"가 아니라 "원격이 로컬을 포함한다"를 판정 |

권한 설계: ClickHouse(uid 101)가 바인드 마운트에 쓰려면 디렉터리 소유가 101 이어야 하고(컨테이너 root 로 chown), Airflow(uid 50000)가 101 이 만든 750 디렉터리를 읽으려면 **보조 그룹 101**(compose `group_add`)이 필요했다. ssh 키는 uid 50000 소유 사본(저장소 밖, 600), 호스트키는 핑거프린트 대조 후 고정(StrictHostKeyChecking=yes).

### 용량 산정 (실측)
| 항목 | 값 |
|---|---|
| 호가 하루 Parquet | 09-15: lz4 977MB / **zstd 726MB**(19,739,553행, 70초, 피크 메모리 136MiB) · 09-16: zstd 647MB |
| 120일 롤링 | ≈ 85GB |
| ClickHouse 백업 | 전체 3.9GB × 3세대 + 증분(첫 증분 80MB/일) ≈ 15~20GB |
| 합계 | ≈ 105GB < 147GB. **180일(6개월)은 안 들어간다** → 120일로 확정. 이전 문서의 "6개월 롤링"은 실측 전 추정이었다 |

### 첫 실행에서 배운 것
수동 `dags test` 는 논리 날짜로 실행일을 역산해 증분이 `incr_20260915` 로 이름 붙었고, 원격 보존 정책이 "최신 전체(20260917)보다 오래된 증분"으로 판단해 즉시 삭제했다.
그 결과 검증 태스크가 "로컬에 있는데 원격에 없음" 259파일로 실패했다. 스케줄 실행에서는 실행일 = 당일이라 생기지 않는 테스트 산물 문제이고, 산물을 지운 뒤 검증은 통과했다.
정책이 의도대로 동작한(오래된 증분 삭제) 것이기도 하다. 첫 스케줄 실행은 09-18 01:20 UTC.

- 복원 리허설은 분기마다 수동(§1 절차 그대로).

## 5. 후기·정정
- n8n 경위는 §2 에 그대로 남겼다(판단 오류 2건, 초안 편집 3회 무효, 재시작 5회). 최종 상태: 발행 버전이 암호화 자격증명으로 인증, DB 세 테이블 어디에도 평문 비밀번호 없음(검증 쿼리 0/0/0).
- 자격증명 가져오기(`n8n import:credentials`)는 JSON 에 `id` 가 없으면 NOT NULL 위반으로 실패한다 — 명시해서 해결.
- 컷오버 창 독립 재대조(별도 도구 dry-run, 00:43:20~00:44:30, 287마켓): 원장 2,797 / 보유 2,797 / **누락 0**.
