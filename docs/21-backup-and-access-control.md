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

---

## 5. 접근 통제 재점검 (2026-09-20 08:10 ~ 08:30 UTC)

> 사용자: "방화벽은 무조건 닫는 게 맞잖아. 또 다른 건?"
> 방화벽을 실행하기 **전에** 실제 상태를 다시 셌다. 목록이 부족했고, 목록에 없던 더 큰 구멍이 있었다.

### 5-1. 가장 큰 구멍 — Grafana 익명 + 쓰기 권한 데이터소스

| 사실 | 확인 방법 |
|---|---|
| Grafana 는 **익명 Viewer 를 허용**한다 (`GF_AUTH_ANONYMOUS_ENABLED=true`) | compose |
| 익명으로 대시보드 5개 목록이 조회된다 | `GET /api/search` 인증 없이 200 |
| 익명으로 **임의의 SQL 이 실행된다** | `POST /api/ds/query` 에 `rawSql` 을 넣어 `SELECT count() FROM crypto_trades` → **117,460,641** 반환 |
| 그 데이터소스가 쓰던 계정이 **`pipeline`** 이었다 | `GET /api/datasources` 의 `jsonData.username` |
| `pipeline` 의 권한 | `SELECT, INSERT, ALTER, CREATE TABLE, CREATE VIEW, **DROP TABLE**, TRUNCATE, OPTIMIZE, BACKUP ON cdc_pipeline.*` |

**즉, 포트 3000 은 "인증 있는 UI" 가 아니라 인증 없는 SQL 게이트웨이였다.**
방화벽 계획은 3000 을 "인증이 있으니 열어 둔다"고 적고 있었다 — 전제가 틀렸다.
docs/34 #1 에서 포트를 닫으면서도 이 경로를 못 본 이유는 **포트만 세고 그 뒤에 무엇이 있는지 안 봤기 때문**이다.

**조치**: 데이터소스를 `readonly_user`(SELECT 전용)로 내렸다. 대시보드가 system 테이블을 쓸 수 있어
`GRANT SELECT ON system.*` 을 먼저 주었다(읽기라 새 위험 없음).

| 검증 | 결과 |
|---|---|
| 데이터소스 계정 | `pipeline` → **`readonly_user`** |
| 익명 읽기(대시보드) | 여전히 200, `count() = 117,464,003` — **대시보드 안 깨진다** |
| 익명 쓰기 | `CREATE TABLE … Memory` → **`code 497: readonly_user: Not enough privileges`**, 표는 안 만들어짐(0) |

**남은 판단은 사용자 몫**: 익명 Viewer 자체를 끌지(`GF_AUTH_ANONYMOUS_ENABLED=false`), 3000 도 방화벽에 넣을지.
지금 상태는 "LAN 에서 로그인 없이 **읽기만**" 이다. 그게 의도라면 이대로 두면 된다.

### 5-2. 방화벽 차단 목록이 부족했다
`ss -ltnp` 로 0.0.0.0 에 실제로 열린 것을 다시 셌더니 이 저장소 밖 컨테이너 둘이 더 있었다.

| 포트 | 무엇 | 왜 문제 |
|---|---|---|
| 6379 | `fds-redis` | `redis-server --appendonly yes` 뿐 — **requirepass 가 없다.** LAN 의 누구나 읽고 쓸 수 있고 CONFIG SET 으로 파일을 쓰는 알려진 경로가 있다 |
| 5432 | `my-postgres` | 비밀번호는 있으나 DB 포트를 LAN 에 둘 이유가 없다 |

둘 다 이 프로젝트 것이 아니지만 **같은 미니PC 이고, 뚫리면 같은 호스트다.** `lan-firewall.sh` 의 PORTS 에 추가했다.

### 5-3. 그 밖에 확인한 것 (문제 없음 / 조치함)
| 항목 | 결과 |
|---|---|
| `.env` 권한 | **644 → 600 으로 조치.** 모든 DB 비밀번호가 들어 있는데 누구나 읽을 수 있었다 |
| `secrets/` | 700, 개인키 600 — 정상 |
| Airflow SSH 키(`oci_key`) | 600 — 정상 |
| git 이력에 비밀 파일 | **없음.** `.env`·`secrets/`·`*.pem` 이 커밋된 적 없고 `.gitignore` 에 있다 |
| 추적 파일의 하드코딩 비밀번호 | 없음 (전부 `env_var`·`${}` 참조) |
| Docker 소켓을 받은 컨테이너 | **없음** — DAG 들이 "네트워크 API 기반" 원칙을 지킨 결과 |
| privileged 컨테이너 | 없음 |
| SSH | `PasswordAuthentication no` (키 전용) |
| Airflow UI 8085 | 로그인 화면 강제, `/api/v1/dags` 인증 없이 **401** — 정상 |
| MySQL 3306 · Connect 8083 · ClickHouse 8123 | 127.0.0.1 바인딩(docs/34 #1) |
| ops 프로필(kafka-ui·Prometheus·statsd) | 꺼져 있음 |

### 5-4. 곁가지 — 죽은 데이터소스
`ClickHouse - Circuit Connect` 데이터소스는 계정 없이 `default` 로 붙으려다
`code 516 Authentication failed` 로 **실패한다.** 보안 구멍은 아니지만 그 대시보드는 안 돈다.
docs/35 §4 의 "죽은 참조" 와 같은 부류 — 다른 프로젝트 소유라 여기서는 기록만 한다.

### 5-5. 배운 것
**포트를 세는 것과 그 뒤에 무엇이 있는지 보는 것은 다른 일이다.**
docs/34 #1 은 "무인증 노출 포트"를 세어 닫았는데, 정작 가장 넓게 열린 문은
**인증이 있다고 적어 둔 포트 뒤에** 있었다. 다음부터 접근 통제 점검은 포트 목록이 아니라
"인증 없이 무엇을 할 수 있나"를 **실제로 해 보는 것**으로 시작한다.

### 5-6. 방화벽 실행과 포트 바인딩 (2026-09-20 09:10 ~ 09:30 UTC)

#### 스크립트가 두 번 틀렸다 — 한 번도 안 돌려 봤기 때문에
`lan-firewall.sh` 는 09-19 에 쓰였지만 **한 번도 실행된 적이 없었다.** 사용자가 처음 돌리자 바로 죽었다.

| # | 증상 | 원인 | 고침 |
|---|---|---|---|
| 1 | `iptables v1.8.10 (nf_tables): Port "9092,2181,..." does not resolve to anything.` | conntrack 의 `--ctorigdstport` 는 **포트 하나(또는 a:b 범위)만** 받는다. 쉼표 목록을 못 받는데 multiport 와 같은 변수를 넘기고 있었다 | 포트마다 한 줄씩 |
| 2 | (고치다 내가 만들 뻔한 것) 규칙은 들어가는데 아무것도 안 막힘 | `-A`(끝에 추가)를 쓰면 **DOCKER-USER 의 마지막 도커 기본 RETURN 뒤**라 평가되지 않는다 | `-I DOCKER-USER 4` (허용 3줄 다음) |

②는 실행 전에 잡았다. 그래서 검증도 **개수가 아니라 자리까지** 보게 바꿨다 —
"규칙이 들어갔다"와 "규칙이 작동한다"는 다르다.

`--ctorigdstport` 를 쓰는 이유: 도커는 DNAT 를 먼저 한다. FORWARD(=DOCKER-USER)에 도달할 때
목적지 포트는 이미 컨테이너 포트라, 우리가 막으려는 **퍼블리시된 호스트 포트**로 판단하려면
conntrack 이 기억하는 원래 목적지 포트를 봐야 한다.

**결과**: 허용 3줄(도커 172.16/12 · WireGuard 10.88.0.0/24 · 루프백) 뒤에 DROP 7줄. 파이프라인 무영향
(Flink 5잡 RUNNING, 커넥터 2개 RUNNING, 2분간 Upbit 3,678 · Binance 30,205 · 호가 25,248행 적재 지속).

#### 포트 퍼블리시를 내렸다 — SASL 이전에 할 일이 있었다
Kafka 의 EXTERNAL 리스너는 `advertised=localhost:9092` 다. **다른 기기가 붙으면 브로커가 "localhost 로 오라"고
답하므로 애초에 쓸 수 없었다.** 즉 9092 가 LAN 에 열려 있던 것은 쓸모는 없고 공격면만 되는 상태였다.
ZooKeeper 2181 은 호스트에서 쓰는 곳이 아예 없었다(grep·연결 모두 0).

| 변경 | 전 | 후 |
|---|---|---|
| Kafka | `9092:9092` | `127.0.0.1:9092:9092` |
| ZooKeeper | `2181:2181` | 퍼블리시 제거 |

부하 실험 스크립트(`pipeline-load-lab`)가 `localhost:9092` 를 기본값으로 쓰는데 127.0.0.1 바인딩이라 그대로 동작한다.

#### 재생성 실측 — 유실 0, 지연만 한 분
| 항목 | 값 |
|---|---|
| 재생성 | `docker compose up -d zookeeper kafka-1` **5.3초**, 브로커 API 응답까지 **47초** |
| 체결 무손실 | 정지 구간 MySQL **3,057** = ClickHouse **3,057**, 차이 **0** |
| 호가 수집기 | `deliv_err=0 buf_err=0` (기준값 그대로) |
| Binance 수집기 | `deliv_err=0 buf_err=0 reconnects=0`, lag p95 19ms |
| Debezium | 두 커넥터 모두 RUNNING — **태스크 FAILED 없었다**(MySQL 재생성 때와 달리 브로커만 내려가면 Connect 가 스스로 재연결한다) |
| Flink | 5잡 RUNNING, 체크포인트에서 재개 |
| 지연 | e2e p95 4.4s → **51s (09:25 한 분)** → 4.9s. 밀렸다가 따라잡은 것이지 잃은 것이 아니다 |

버퍼가 견딘 근거: 수집기 큐 20만 건 · `message.timeout.ms=600000`(10분). 유입이 Binance 약 349/s,
Upbit 호가 약 250/s 라 각각 9분·13분치다. 정지가 1분이었으니 여유가 컸다.

**남은 0.0.0.0**: 22(SSH 키 전용) · 3000(Grafana) · 8085(Airflow UI) · 8089(다른 프로젝트 API) · 80/443(웹)
· 5432(my-postgres, 다른 프로젝트) · 8081(Flink). 뒤 둘은 **방화벽으로만** 막혀 있다 —
8081 바인딩을 내리려면 잡매니저 재생성 = 잡 5개 세이브포인트·재제출이라 별도 창으로 미뤘고,
5432 는 우리 소유가 아니다.

#### 5-7. Redis 제거 (사용자 실행, 09-20)
`fds-redis` 는 `fds-pipeline-lab`(7개월 전 프로젝트)의 잔재였다. 같은 프로젝트의 generator·consumer 는
7개월 전에 종료됐고 Redis 만 11일째 혼자 떠 있었다. **키 0개, 붙은 클라이언트 0개.**
비밀번호를 거는 것이 아니라 **지우는 것이 맞는 조치**였다 — 아무도 안 쓰는 서비스에 인증을 붙이면
공격면은 그대로 두고 운영 부담만 는다. 사용자가 컨테이너를 제거했고 6379 는 더 이상 listen 하지 않는다.
데이터 볼륨은 남아 있어 그 프로젝트를 되살리면 복구된다(그때는 `--requirepass` + 127.0.0.1 바인딩과 함께).

#### 5-8. "그럼 Grafana 는 이상 없나" — 익명이 할 수 있는 일의 정확한 범위 (09-20 09:45 UTC)

사용자: "다른 사람은 뷰잉만 되는 거 아냐?" → **쓰기는 맞고, '뷰잉'의 범위는 대시보드보다 넓다.** 실측으로 확인했다.

| 확인 | 결과 |
|---|---|
| 쓰기 | `INSERT` → `code 497 ACCESS_DENIED`. `CREATE TABLE` 도 동일. **막혔다** |
| 읽기 범위 | `cdc_pipeline` · `circuit_connect` · `system` 세 데이터베이스. 대시보드에 없는 표도 **임의 SELECT 가능** |
| 자격증명 노출 | **없음.** `system.users.auth_params` 는 전부 `{}` 로 마스킹된다(평문 비밀번호인 `default` 도 값이 안 보인다) |
| `system.query_log` | 읽힌다. 파이프라인 쿼리 원문이 보인다. 다만 비밀번호 패턴 검색 결과 **0건** |
| `system` 권한이 필요한가 | **그렇다.** 대시보드가 24시간에 **587회** system 테이블을 조회했다 — 회수하면 패널이 깨진다 |
| 민감도 | 시세는 공개 데이터, 원장은 테스트넷(실돈 없음). 다만 `circuit_connect.dim_users` **780행에 nickname** 이 있다(이메일·전화는 없음) |

**판단**: 파이프라인 관점에서는 이상 없다. 남는 것은 "게임 사용자 닉네임 780건을 LAN 에서 로그인 없이 읽을 수 있어도 되는가"
하나이고, 그건 데이터 소유자의 판단이다. 좁히려면 ① 익명 끄기(`GF_AUTH_ANONYMOUS_ENABLED=false`)
② 3000 도 방화벽에 추가 ③ Grafana 에 데이터소스를 나눠 익명에는 `cdc_pipeline` 만 주기 — 셋 중 하나다.

#### 5-9. Grafana 가 인터넷에 공개돼 있었다 — 포트만 보면 놓친다 (09-20 10:00 ~ 10:20 UTC)

**내가 앞에서 틀렸다.** §5-8 에서 "익명 접근은 LAN 범위"라고 적었는데, 사실이 아니었다.
포트 3000 은 공유기가 막고 있었지만(휴대폰 셀룰러 테스트에서 로딩만 계속됨 = 포워딩 없음),
**Caddy 가 `grafana.calmee.store` 를 인증 없이 3000 으로 넘기고 있었다.**

```
grafana.calmee.store {
    reverse_proxy localhost:3000        # ← airflow·code 블록과 달리 basic_auth 가 없다
}
```

| 실측 | 결과 |
|---|---|
| `https://grafana.calmee.store/` | **HTTP 200** (인증 없이) |
| `/api/search` | **200** — 대시보드 목록 |
| `/api/ds/query` 에 rawSql | **200, SQL 실행됨** |

즉 §5-1 에서 "LAN 에 열린 SQL 게이트웨이"라고 적은 것은 **인터넷에 열린 SQL 게이트웨이**였다.
그리고 §5-1 을 고치기 전까지 그 경로가 쓰던 계정은 `pipeline`(DROP TABLE 권한)이었다.

**교훈(§5-5 의 반복이자 강화)**: 포트를 세는 것만으로는 부족하고, **리버스 프록시 설정까지 봐야 한다.**
포트는 막혀 있는데 도메인으로는 뚫려 있는 것이 정확히 이 경우다. 점검 순서는
`ss` → `iptables` → **웹서버 설정** → 각 서비스의 인증 순이어야 한다.

#### 5-10. 결정: 공개하지 않는다
사용자: "일반적으로 Grafana 대시보드는 밖에 안 보여주나? 그럼 그 규율을 따를게. 포폴용으로 캡처 정도면 되잖아."

맞다. Grafana 는 내부 운영 도구이고, 공개할 때는 SSO·VPN·basic auth 뒤에 둔다.
포트폴리오 목적이라면 캡처나 짧은 녹화로 충분하고, 공개 대시보드는 운영 정보(쿼리 원문·표 구조·적재량)를
같이 내보내며 무차별 로그인 시도의 표적이 된다.

| 최종 상태 | 값 |
|---|---|
| `GF_AUTH_ANONYMOUS_ENABLED` | **false** |
| 데이터소스 계정 | `readonly_user` (SELECT 전용 — 로그인한 사람에게도 쓰기는 안 준다) |
| 인터넷 `/api/search` | **401** |
| 인터넷 `/api/ds/query` | **401** |
| 로그인 후 | 200 (정상 사용) |

중간에 "공개는 유지하되 계정을 더 좁히자"며 `grafana_public`(cdc_pipeline + 대시보드가 실제 쓰는 system 표 6개)을
만들었다가, 공개를 닫기로 하면서 **삭제했다.** 안 쓰는 계정을 남기면 그것도 공격면이다.

**남은 한 단계(사용자 sudo)**: Caddy 의 grafana 블록에도 basic_auth 를 붙이면 Grafana 로그인 화면 자체가
인터넷에 노출되지 않는다. 지금은 Grafana 설정 하나가 유일한 잠금이다.

#### 5-11. 곁가지 — 공개 저장소에 박혀 있던 비밀번호, 그리고 조용히 죽어 있던 cron
`scripts/sync-annotations.sh` 에 `-u admin:cdc_grafana_2025` 가 하드코딩돼 있었다. 공개 GitHub 저장소다.
다행히 **옛 값**이라 유출 피해는 없었는데, 그 말은 **이 cron 이 매분 403 으로 실패하고 있었다**는 뜻이다.
09-20 #7 에서 이 스크립트를 "죽은 참조"라고 고쳤는데, 인증까지는 안 봤다 — 고쳤다고 믿고 결과를 안 본 것이다.

- `.env` 에서 읽도록 수정. 비밀값을 코드에 두면 바뀔 때 같이 안 바뀌고 조용히 죽는다.
- 실패를 버리던 `> /dev/null` 을 상태코드 출력으로 바꿔 200 이 아니면 로그에 남게 했다.
- 검증: 현재 비밀번호로 POST **200**, 옛 비밀번호로 **403**.

**내 1차 비밀값 스캔이 이걸 놓친 이유**: 정규식이 `password=`·`key:` 꼴만 봤고 `-u user:pass` 형태를 안 봤다.
스캔은 패턴이 아니라 **자격증명이 들어갈 수 있는 모든 형태**를 생각해야 한다.
