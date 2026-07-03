# Phase 6: 이상 탐지 임계값 조정 + Flink 장애 복구 + 대시보드 개선

## 날짜: 2026-02-23

---

## 1. 이상 탐지 임계값 조정 (업비트 정책 + 학술 근거)

### 문제: v1 임계값이 과다 알림 생성

| 규칙 | v1 임계값 | 1시간 알림 | 비율 | 문제 |
|------|----------|----------|------|------|
| VOLUME_SURGE | EMA×10 | 512건 | 74% | 10배 기준이 너무 낮음 |
| PRICE_SPIKE | 0.5% (고정) | 131건 | 19% | DOGE 1원(0.73%) 변동이 매번 발동 |
| RAPID_TRADES | 50건/10초 | 28건 | 4% | 적절 |
| LARGE_TRADE | BTC 1억/기타 5천만 | 20건 | 3% | 적절 |

**총 651건/시간 → 알림 의미 희석**

### 조사: 업비트 이상거래 감시정책 (가상자산이용자보호법 기반)

업비트 공식 7가지 불공정거래 모니터링 유형:

| # | 유형 | 설명 | 우리 파이프라인 매핑 |
|---|------|------|-------------------|
| 1 | 가장·통정성 매매 | 자전거래, 권리이전 없는 가장매매 | ❌ (계좌 정보 없음) |
| 2 | 허수성 매매 | 체결 불가능한 대량 호가 제출 | ❌ (호가 데이터 없음) |
| 3 | 취소·정정 과다 | 체결률 극히 낮은 반복 주문 | ❌ (주문 데이터 없음) |
| 4 | **특정종목 매매집중** | 과도한 매매로 시세 영향 | → **RAPID_TRADES** |
| 5 | **체결관여 과다** | 전체 체결 대비 과도 집중 | → **VOLUME_SURGE** |
| 6 | 주문관여 과다 | 전체 주문 대비 과도 제출 | ❌ (주문 데이터 없음) |
| 7 | **시세관여 과다** | 시세 변동에 과도 관여 | → **PRICE_SPIKE** |

**결론**: 우리 파이프라인은 체결(trade) 데이터만 수신하므로, 7가지 중 체결 기반 3가지 + 대량체결 1가지만 구현 가능.

### 학술 근거

- **"Detecting Crypto Pump-and-Dump Schemes" (2025, arXiv:2503.08692)**
  - EWMA(지수가중이동평균) + 변동성 기반 동적 임계값으로 펌프앤덤프 탐지
  - 고정 임계값이 아닌, 코인별 과거 패턴 대비 이상치를 탐지하는 접근
  - 우리 VOLUME_SURGE에 EMA 기반 동적 임계값 적용의 근거

### v2 임계값 (최종 적용)

| 규칙 | v1 | v2 | 근거 |
|------|-----|-----|------|
| LARGE_TRADE | BTC 1억, 기타 5천만 | **BTC 5억, ETH 3억, 기타 1억** | 업비트 일 거래대금 2조+ 기준, 1억은 소액 |
| PRICE_SPIKE | 0.5% (고정) | **BTC 2%, 기타 3%** (마켓별 동적) | DOGE 1원(0.73%) 제외, BTC 2%=194만원 변동 |
| VOLUME_SURGE | EMA×10 | **EMA×50, 최소 50건 학습** | 학술 근거: EWMA 기반, 충분한 학습 후 판단 |
| RAPID_TRADES | 50건/10초 | **100건/10초** | BTC 정상적으로 50건/10초 가능 |

### 검증 결과 (순수 실시간 5분간)

| 규칙 | 건수 | 시간당 환산 |
|------|------|-----------|
| VOLUME_SURGE | 6건 | ~72건 |
| PRICE_SPIKE | 0건 | 진짜 급등락만 포착 |
| LARGE_TRADE | 0건 | 대형 거래만 포착 |
| RAPID_TRADES | 0건 | 비정상 빈도만 포착 |

**v1: 651건/시간 → v2: ~72건/시간 (89% 감소, 의미 있는 알림만 잔존)**

---

## 2. Flink 장애 복구 (MySQL Cleanup → Flink Crash)

### 장애 타임라인

```
2026-02-21 04:00:00 — MySQL Event Scheduler 실행 (cleanup_old_trades)
2026-02-21 04:00:04 — 대량 DELETE 50,000건 발생
2026-02-21 04:00:04 — Debezium → Kafka로 DELETE 이벤트 + tombstone 메시지 전달
2026-02-21 04:00:33 — Flink CdcEventParser에서 NullPointerException
2026-02-21 04:00:35 — Flink Job restart 3회 실패 → FAILED
2026-02-21 04:00:36 — 모든 Task CANCELED, JobManager connection 해제
2026-02-23 02:21:00 — 장애 발견 (약 46시간 동안 ClickHouse 적재 중단)
```

### 근본 원인 분석 (3가지 동시 문제)

**문제 1: Debezium tombstone 메시지**
- MySQL DELETE → Debezium이 2종류 메시지 생성:
  1. DELETE 이벤트 (op=d, before 데이터 포함)
  2. Tombstone 메시지 (key만 있고 value=null)
- `SimpleStringSchema.deserialize(null)` → NullPointerException

**문제 2: CdcEventParser가 DELETE 미처리**
- 파서가 INSERT(op=c)만 고려하고 DELETE(op=d) 처리 로직 없음
- DELETE 이벤트의 after=null 접근 시 NPE 발생

**문제 3: Flink restart 전략 미설정**
- 기본값: 에러 발생 시 즉시 FAILED (재시도 없음)
- 일시적 에러에도 Job 전체가 죽음

### 해결 방법 (3가지 수정)

**수정 1: NullSafeStringSchema 구현**
```java
// SimpleStringSchema → NullSafeStringSchema
public class NullSafeStringSchema implements DeserializationSchema<String> {
    @Override
    public String deserialize(byte[] message) {
        if (message == null) return null;  // tombstone 안전 처리
        return new String(message, StandardCharsets.UTF_8);
    }
}
```

**수정 2: CdcEventParser 방어적 파싱**
```java
// DELETE 이벤트 스킵
if ("d".equals(op)) return;

// null/빈 메시지 무시 (tombstone)
if (json == null || json.isEmpty() || json.equals("null")) return;

// 모든 필드 null-safe 헬퍼 메서드
private long safeGetLong(JsonNode data, String field) {
    if (data == null || !data.has(field) || data.get(field).isNull()) return 0L;
    return data.get(field).asLong();
}
```

**수정 3: Flink restart 전략 + latest offset**
```java
// 에러 시 10초 간격으로 3번 재시도
env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.seconds(10)));

// checkpoint 없이 시작할 때 최신 데이터부터
.setStartingOffsets(OffsetsInitializer.latest())

// source 뒤에 null 필터 추가
.filter(msg -> msg != null)
```

### MySQL Cleanup 이벤트 개선

**Before (문제)**
```sql
EVERY 1 DAY, LIMIT 50,000
→ 하루 한번에 50K건 DELETE → CDC 폭주 → Flink 사망
```

**After (개선)**
```sql
EVERY 1 HOUR, LIMIT 25,000
→ 매시간 25K건 DELETE → CDC 부하 분산
→ 25K × 24시간 = 600K/일 > 580K/일 적재량 → 정리 충분
```

### 복구 절차

```bash
# 1. Flink checkpoint 초기화
docker exec cdc-flink-jobmanager rm -rf /opt/flink/checkpoints/*
docker exec cdc-flink-taskmanager rm -rf /opt/flink/checkpoints/*

# 2. Kafka offset을 latest로 리셋 (문제 구간 건너뛰기)
docker exec cdc-kafka-1 kafka-consumer-groups \
  --bootstrap-server kafka-1:29092 \
  --group flink-cdc-consumer \
  --topic cdc.crypto_db.crypto_trades \
  --reset-offsets --to-latest --execute

# 3. Flink Job 재제출
docker exec cdc-flink-jobmanager flink run -d \
  /opt/flink/usrlib/flink-cdc-job-1.0.0.jar
```

---

## 3. Grafana 대시보드 개선

### 이상 탐지 Annotation (빨간 마커)

- **방식**: Grafana REST API로 annotation 생성
- **대상 패널**: BTC Price (Realtime) + Trade Volume by Market
- **자동화**: cron 매분 실행 (`scripts/sync-annotations.sh`)
- **결과**: BTC 가격 차트에 이상 탐지 시점이 빨간 점선으로 표시

```bash
# sync-annotations.sh — 매분 실행
# ClickHouse anomaly_alerts → Grafana annotation API
# dashboardId=3, panelId=12 (BTC Price)
```

### CDC Latency 색상 분리

- **Before**: avg_latency, max_latency 같은 색
- **After**: avg_latency=녹색, max_latency=주황색

### 숫자 포맷 통일

- **Trade Volume Y축**: `2000000000` → `₩2B`
- **Recent Trades trade_price**: `96889000` → `₩96,889,000`
- **Recent Trades trade_amount**: `₩15.0K` 포맷

### Pipeline Status 패널 수정

- **Before**: 문자열 반환 → "No data"
- **After**: 숫자 반환 (1=LIVE, 0=STALE) + Value mapping

---

## 4. 수정된 파일 목록

| 파일 | 변경 내용 |
|------|----------|
| `flink/.../function/AnomalyDetector.java` | v2 임계값, 마켓별 동적 기준, Javadoc 근거 |
| `flink/.../function/CdcEventParser.java` | null-safe 파싱, DELETE 스킵, 방어적 처리 |
| `flink/.../function/NullSafeStringSchema.java` | **신규** — tombstone 안전 Deserializer |
| `flink/.../CdcPipelineJob.java` | latest offset, restart 전략, null 필터 |
| `grafana/.../cdc-pipeline.json` | annotation, 색상, 숫자 포맷, Pipeline Status |
| `scripts/sync-annotations.sh` | **신규** — Grafana annotation 자동 동기화 |
| MySQL Event Scheduler | EVERY 1H LIMIT 25K |

---

## 5. 커밋 이력

```
feat: tune anomaly thresholds based on Upbit policy + research
fix: Flink tombstone crash + dashboard improvements
```

---

## 6. 현재 파이프라인 상태

- **전체 파이프라인**: RUNNING ✅
- **Total Trades**: 6,679,333건 (10일 운영)
- **CDC Latency**: 평균 3.30ms
- **Active Alerts**: ~72건/시간 (v2 임계값)
- **Markets**: 5개 (BTC, ETH, XRP, SOL, DOGE)
- **Pipeline Status**: LIVE
- **SSD 사용**: 안정적 (10.7GB 최대, 66GB 여유)
