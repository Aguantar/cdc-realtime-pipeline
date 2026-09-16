# 16-부록. 이상탐지 기준 도출에 쓴 쿼리·코드와 결과 (재실행 2026-09-16 12:30 UTC)

이 파일은 `scripts/analysis/rule_basis_check.sh`의 출력을 그대로 옮긴 것이다. 문서 docs/16의 모든 숫자는 이 스크립트를 다시 돌려 나온 값과 일치해야 하고, 실제로 일치했다(재실행 전후 차이는 Q0의 GLOBAL_PRICE_DIFFERENCES 10,546→10,549, 시간별 증분 동기화로 3건 추가된 것뿐).

## 사고 과정 (순서대로)
1. 기존 3규칙의 명분을 원문으로 확인 → 업비트 감시정책 7유형은 계정 단위 → 재현 불가 (docs/16 §1).
2. 대안 참조 탐색 → 시장경보제도(자동 지정, 5유형). 공식 문서는 Zendesk API로 취득 (§2).
3. 정답 데이터 탐색 → 처음엔 "이력 없음" 단정(오류) → 웹 번들 추적 → `market-event-records` API → 6개월 15,588건 적재 (`scripts/labels/fetch_market_event_records.py`) (§3).
4. 라벨 구조 파악(Q0): 유형별 건수·지속시간·지정 시각 패턴. 거래량·입금·소수계정은 매일 01:00 UTC 일괄, 가격·글로벌은 분 단위 → 규칙의 시간 해상도가 여기서 결정됨.
5. 가격: 문서가 말한 지표(24h 전 대비)를 지정 시각에 계산(Q1·Q2) → 50%·100%에 집중. 역방향 검증(Q3): 지표≥50%인 분이 에피소드 밖에 있는가 → 1/3,221. 재현율(Q4).
6. 자체 감사: 표본 독립성(Q5) 부족 인정 → 6개월 (마켓,일,등급)당 1건을 업비트 1분봉으로 독립 재계산(Q10, `scripts/analysis/verify_price_threshold.py`) → 월별 안정, 05-18 기준 변경과 일치.
7. 거래량: 일봉 200일 적재(`scripts/analysis/fetch_daily_candles.py`) → 기준 창·임계 스윕(Q6), 절대 하한(Q7). 감사: 라벨 날짜 어긋남 민감도(Q8), 기준 변형(Q9).
8. 결론과 한계를 docs/16 §4·§5에 기록. 적용은 미결.

## 입력 데이터
- `crypto_trades`: 우리 파이프라인 체결 원본(287마켓, 2026-09-09~). 가격 분 종가 = 그 분의 마지막 체결가(`argMax(trade_price, upbit_timestamp)`).
- `upbit_market_event_records`: 거래소 경보 이력(에피소드당 1행, `FINAL`로 중복 제거).
- `upbit_daily_candles`: 업비트 REST 일봉 200일(UTC 일 경계 = KST 09:00).
- 관찰 주간 창은 09-10 06:10부터(백필 창 제외, docs/14).

## 쿼리와 결과


## Q0 라벨 개요: 유형별 에피소드 수·현재 진행 중·일평균·지속시간
```sql

SELECT event_type, count() episodes, countIf(trigger_type='TRIGGER') active_now,
       round(count()/dateDiff('day', min(trigger_time_utc), max(trigger_time_utc)),1) per_day,
       round(quantile(0.5)(dateDiff('minute', trigger_time_utc, expiration_time_utc))) dur_p50_min
FROM upbit_market_event_records FINAL GROUP BY event_type ORDER BY episodes DESC
```
```text
┌─event_type──────────────────────┬─episodes─┬─active_now─┬─per_day─┬─dur_p50_min─┐
│ GLOBAL_PRICE_DIFFERENCES        │    10549 │          3 │    59.6 │           3 │
│ TRADING_VOLUME_SOARING          │     2189 │         16 │    12.4 │        1440 │
│ PRICE_FLUCTUATIONS              │     1538 │          0 │     8.8 │           4 │
│ DEPOSIT_AMOUNT_SOARING          │     1272 │         13 │     7.2 │        1440 │
│ CONCENTRATION_OF_SMALL_ACCOUNTS │       43 │          1 │     0.3 │        1440 │
└─────────────────────────────────┴──────────┴────────────┴─────────┴─────────────┘
```

## Q1 가격: 관찰 주간 PRICE_FLUCTUATIONS 지정 시각의 |분 종가 / 24h 전 분 종가 - 1| (ASOF 조인, 우리 체결 데이터)
```sql

WITH ep AS (SELECT market, warning_level, trigger_time_utc t, trigger_time_utc - INTERVAL 24 HOUR t24
            FROM upbit_market_event_records FINAL
            WHERE event_type='PRICE_FLUCTUATIONS' AND trigger_time_utc >= '2026-09-10 06:10:00' AND trigger_time_utc < '2026-09-16 06:10:00'),
     px AS (SELECT market, toStartOfMinute(fromUnixTimestamp64Milli(upbit_timestamp)) m, argMax(trade_price, upbit_timestamp) p
            FROM crypto_trades WHERE source_ts >= '2026-09-09 00:00:00' AND market IN (SELECT market FROM ep) GROUP BY market, m),
     j1 AS (SELECT ep.market, ep.warning_level, ep.t, ep.t24, px.p p_now FROM ep ASOF JOIN px ON ep.market = px.market AND px.m <= ep.t),
     j  AS (SELECT j1.*, px.p p_24h FROM j1 ASOF JOIN px ON j1.market = px.market AND px.m <= j1.t24)
SELECT warning_level, count() n, round(min(abs(p_now/p_24h-1))*100,1) min_pct, round(quantile(0.1)(abs(p_now/p_24h-1))*100,1) p10,
       round(quantile(0.5)(abs(p_now/p_24h-1))*100,1) p50, round(max(abs(p_now/p_24h-1))*100,1) max_pct, countIf(p_now>p_24h) up, countIf(p_now<p_24h) down
FROM j WHERE p_now>0 AND p_24h>0 GROUP BY warning_level ORDER BY warning_level
```
```text
┌─warning_level─┬───n─┬─min_pct─┬──p10─┬───p50─┬─max_pct─┬──up─┬─down─┐
│ LEVEL_1       │ 198 │    46.2 │ 49.2 │  51.2 │   103.8 │ 182 │   16 │
│ LEVEL_2       │  28 │    97.4 │ 98.4 │ 100.9 │   113.8 │  28 │    0 │
└───────────────┴─────┴─────────┴──────┴───────┴─────────┴─────┴──────┘
```

## Q2 가격: 같은 지표의 5% 구간 히스토그램
```sql

WITH ep AS (SELECT market, warning_level, trigger_time_utc t, trigger_time_utc - INTERVAL 24 HOUR t24
            FROM upbit_market_event_records FINAL
            WHERE event_type='PRICE_FLUCTUATIONS' AND trigger_time_utc >= '2026-09-10 06:10:00' AND trigger_time_utc < '2026-09-16 06:10:00'),
     px AS (SELECT market, toStartOfMinute(fromUnixTimestamp64Milli(upbit_timestamp)) m, argMax(trade_price, upbit_timestamp) p
            FROM crypto_trades WHERE source_ts >= '2026-09-09 00:00:00' AND market IN (SELECT market FROM ep) GROUP BY market, m),
     j1 AS (SELECT ep.market, ep.warning_level, ep.t, ep.t24, px.p p_now FROM ep ASOF JOIN px ON ep.market = px.market AND px.m <= ep.t),
     j  AS (SELECT j1.*, px.p p_24h FROM j1 ASOF JOIN px ON j1.market = px.market AND px.m <= j1.t24)
SELECT warning_level, floor(abs(p_now/p_24h-1)*100/5)*5 bin_pct, count() c FROM j WHERE p_now>0 AND p_24h>0 GROUP BY 1,2 ORDER BY 1,2
```
```text
┌─warning_level─┬─bin_pct─┬───c─┐
│ LEVEL_1       │      45 │  45 │
│ LEVEL_1       │      50 │ 118 │
│ LEVEL_1       │      55 │   4 │
│ LEVEL_1       │      60 │   1 │
│ LEVEL_1       │      65 │   1 │
│ LEVEL_1       │      80 │   1 │
│ LEVEL_1       │      85 │   2 │
│ LEVEL_1       │      90 │   3 │
│ LEVEL_1       │      95 │  17 │
│ LEVEL_1       │     100 │   6 │
│ LEVEL_2       │      95 │   6 │
│ LEVEL_2       │     100 │  19 │
│ LEVEL_2       │     105 │   2 │
│ LEVEL_2       │     110 │   1 │
└───────────────┴─────────┴─────┘
```

## Q3 가격 역검증(정밀도): 287마켓 전체에서 |24h 변동| >= 50% 인 분이 거래소 에피소드(±2분) 안에 있는가
```sql

WITH px AS (SELECT market, toStartOfMinute(fromUnixTimestamp64Milli(upbit_timestamp)) m, argMax(trade_price, upbit_timestamp) p
            FROM crypto_trades WHERE source_ts >= '2026-09-09 00:00:00' AND source_ts < '2026-09-16 06:10:00' GROUP BY market, m),
     chg AS (SELECT a.market, a.m FROM px a JOIN px b ON a.market=b.market AND b.m = a.m - INTERVAL 24 HOUR
             WHERE a.m >= '2026-09-10 06:10:00' AND a.m < '2026-09-16 06:10:00' AND abs(a.p/b.p-1) >= 0.5),
     ep AS (SELECT market, trigger_time_utc - INTERVAL 2 MINUTE t0, ifNull(expiration_time_utc, now()) + INTERVAL 2 MINUTE t1
            FROM upbit_market_event_records FINAL WHERE event_type='PRICE_FLUCTUATIONS' AND trigger_time_utc >= '2026-09-09'),
     x AS (SELECT chg.market, chg.m, max(ep.t0 <= chg.m AND chg.m <= ep.t1) inside FROM chg LEFT JOIN ep ON chg.market = ep.market GROUP BY chg.market, chg.m)
SELECT count() minutes_ge50, countIf(inside) inside_ep, countIf(NOT inside) outside, uniqExactIf(market, NOT inside) outside_markets FROM x
```
```text
┌─minutes_ge50─┬─inside_ep─┬─outside─┬─outside_markets─┐
│         3221 │      3220 │       1 │               1 │
└──────────────┴───────────┴─────────┴─────────────────┘
```

## Q4 가격 재현율: 에피소드 안의 모든 분 중 지표가 46%/50%/96% 이상인 비율
```sql

WITH px AS (SELECT market, toStartOfMinute(fromUnixTimestamp64Milli(upbit_timestamp)) m, argMax(trade_price, upbit_timestamp) p
            FROM crypto_trades WHERE source_ts >= '2026-09-09 00:00:00' AND source_ts < '2026-09-16 06:10:00'
              AND market IN (SELECT market FROM upbit_market_event_records WHERE event_type='PRICE_FLUCTUATIONS' AND trigger_time_utc >= '2026-09-10') GROUP BY market, m),
     chg AS (SELECT a.market, a.m, abs(a.p/b.p-1) r FROM px a JOIN px b ON a.market=b.market AND b.m = a.m - INTERVAL 24 HOUR
             WHERE a.m >= '2026-09-10 06:10:00' AND a.m < '2026-09-16 06:10:00'),
     ep AS (SELECT market, warning_level, trigger_time_utc t0, ifNull(expiration_time_utc, now()) t1 FROM upbit_market_event_records FINAL
            WHERE event_type='PRICE_FLUCTUATIONS' AND trigger_time_utc >= '2026-09-10 06:10:00' AND trigger_time_utc < '2026-09-16 06:10:00')
SELECT warning_level, count() ep_minutes, round(countIf(r>=0.46)/count(),3) ge46, round(countIf(r>=0.50)/count(),3) ge50, round(countIf(r>=0.96)/count(),3) ge96, round(min(r)*100,1) min_pct
FROM ep JOIN chg USING market WHERE chg.m >= ep.t0 AND chg.m <= ep.t1 GROUP BY warning_level
```
```text
┌─warning_level─┬─ep_minutes─┬─ge46─┬──ge50─┬──ge96─┬─min_pct─┐
│ LEVEL_2       │        725 │    1 │     1 │ 0.989 │    88.3 │
│ LEVEL_1       │       2755 │ 0.99 │ 0.911 │ 0.049 │    19.4 │
└───────────────┴────────────┴──────┴───────┴───────┴─────────┘
```

## Q5 표본 독립성: 에피소드 수 vs 마켓 수 vs 마켓-일 수 (관찰 주간 / 6개월)
```sql

SELECT 'obs_week' w, count() episodes, uniqExact(market) markets, uniqExact((market, toDate(trigger_time_utc))) market_days FROM upbit_market_event_records FINAL
WHERE event_type='PRICE_FLUCTUATIONS' AND trigger_time_utc >= '2026-09-10 06:10:00' AND trigger_time_utc < '2026-09-16 06:10:00'
UNION ALL SELECT '6_months', count(), uniqExact(market), uniqExact((market, toDate(trigger_time_utc))) FROM upbit_market_event_records FINAL WHERE event_type='PRICE_FLUCTUATIONS'
```
```text
┌─w────────┬─episodes─┬─markets─┬─market_days─┐
│ obs_week │      226 │      14 │          19 │
└──────────┴──────────┴─────────┴─────────────┘
┌─w────────┬─episodes─┬─markets─┬─market_days─┐
│ 6_months │     1538 │      94 │         177 │
└──────────┴──────────┴─────────┴─────────────┘
```

## Q6 거래량: 일봉 거래대금 / 직전 7일(30일) 평균, 라벨 = 다음날 01:00 UTC 지정. 임계 스윕 (6개월)
```sql

WITH c AS (SELECT market, day, amount,
        avg(amount) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) a7,
        avg(amount) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING) a30,
        count() OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING) nb
      FROM upbit_daily_candles FINAL WHERE day >= '2026-03-01'),
 lab AS (SELECT market, toDate(trigger_time_utc) - 1 day FROM upbit_market_event_records FINAL WHERE event_type='TRADING_VOLUME_SOARING' AND toHour(trigger_time_utc)=1 GROUP BY 1,2),
 j AS (SELECT c.*, lab.day != toDate('1970-01-01') pos FROM c LEFT JOIN lab USING (market, day) WHERE nb>=30 AND day >= '2026-03-24' AND day < '2026-09-16')
SELECT k, t, tp, fp, fn, round(tp/(tp+fp),2) precision, round(tp/(tp+fn),2) recall FROM (
 SELECT 'r7' k, t, countIf(amount/a7>=t AND pos) tp, countIf(amount/a7>=t AND NOT pos) fp, countIf(amount/a7<t AND pos) fn FROM j ARRAY JOIN [3,4,5,8] AS t GROUP BY t
 UNION ALL SELECT 'r30', t, countIf(amount/a30>=t AND pos), countIf(amount/a30>=t AND NOT pos), countIf(amount/a30<t AND pos) FROM j ARRAY JOIN [3,4,5,8] AS t GROUP BY t) ORDER BY k, t
```
```text
┌─k───┬─t─┬───tp─┬───fp─┬───fn─┬─precision─┬─recall─┐
│ r30 │ 3 │ 1356 │ 1430 │  625 │      0.49 │   0.68 │
│ r30 │ 4 │ 1192 │  911 │  789 │      0.57 │    0.6 │
│ r30 │ 5 │ 1023 │  646 │  958 │      0.61 │   0.52 │
│ r30 │ 8 │  711 │  281 │ 1270 │      0.72 │   0.36 │
└─────┴───┴──────┴──────┴──────┴───────────┴────────┘
┌─k──┬─t─┬───tp─┬───fp─┬──fn─┬─precision─┬─recall─┐
│ r7 │ 3 │ 1751 │ 1345 │ 230 │      0.57 │   0.88 │
│ r7 │ 4 │ 1579 │  652 │ 402 │      0.71 │    0.8 │
│ r7 │ 5 │ 1419 │  371 │ 562 │      0.79 │   0.72 │
│ r7 │ 8 │  986 │  115 │ 995 │       0.9 │    0.5 │
└────┴───┴──────┴──────┴─────┴───────────┴────────┘
```

## Q7 거래량: 7일 평균 임계 × 절대 하한(원) 조합
```sql

WITH c AS (SELECT market, day, amount, avg(amount) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) a7,
        count() OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING) nb FROM upbit_daily_candles FINAL WHERE day >= '2026-03-01'),
 lab AS (SELECT market, toDate(trigger_time_utc) - 1 day FROM upbit_market_event_records FINAL WHERE event_type='TRADING_VOLUME_SOARING' AND toHour(trigger_time_utc)=1 GROUP BY 1,2),
 j AS (SELECT c.*, lab.day != toDate('1970-01-01') pos FROM c LEFT JOIN lab USING (market, day) WHERE nb>=30 AND day >= '2026-03-24' AND day < '2026-09-16')
SELECT tf.1 t, tf.2 fl, tp, fp, fn, round(tp/(tp+fp),2) prec, round(tp/(tp+fn),2) rec FROM (
  SELECT tf, countIf(amount/a7>=tf.1 AND amount>=tf.2 AND pos) tp, countIf(amount/a7>=tf.1 AND amount>=tf.2 AND NOT pos) fp, countIf(NOT(amount/a7>=tf.1 AND amount>=tf.2) AND pos) fn
  FROM j ARRAY JOIN [(4,0.),(4,5e8),(4,1e9),(5,0.),(5,1e9)] AS tf GROUP BY tf) ORDER BY t, fl
```
```text
┌─t─┬─────────fl─┬───tp─┬──fp─┬──fn─┬─prec─┬──rec─┐
│ 4 │          0 │ 1579 │ 652 │ 402 │ 0.71 │  0.8 │
│ 4 │  500000000 │ 1579 │ 580 │ 402 │ 0.73 │  0.8 │
│ 4 │ 1000000000 │ 1579 │ 426 │ 402 │ 0.79 │  0.8 │
│ 5 │          0 │ 1419 │ 371 │ 562 │ 0.79 │ 0.72 │
│ 5 │ 1000000000 │ 1419 │ 241 │ 562 │ 0.85 │ 0.72 │
└───┴────────────┴──────┴─────┴─────┴──────┴──────┘
```

## Q8 거래량 감사: 라벨 날짜를 -2/-1/0/+1일로 어긋나게 붙였을 때. 맞는 정렬(+1)에서만 성능이 나와야 한다
```sql

WITH c AS (SELECT market, day, amount, avg(amount) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) a7,
        count() OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING) nb FROM upbit_daily_candles FINAL WHERE day >= '2026-03-01'),
 lab AS (SELECT market, toDate(trigger_time_utc) tday FROM upbit_market_event_records FINAL WHERE event_type='TRADING_VOLUME_SOARING' AND toHour(trigger_time_utc)=1 GROUP BY 1,2)
SELECT sh, countIf(pos) positives, countIf(amount/a7>=4 AND pos) tp, countIf(amount/a7>=4 AND NOT pos) fp,
       round(countIf(amount/a7>=4 AND pos)/countIf(amount/a7>=4),2) prec, round(countIf(amount/a7>=4 AND pos)/countIf(pos),2) rec
FROM (SELECT c.*, sh, lab.tday != toDate('1970-01-01') AS pos FROM c ARRAY JOIN [-2,-1,0,1] AS sh LEFT JOIN lab ON c.market=lab.market AND c.day + sh = lab.tday
      WHERE nb>=30 AND day >= '2026-03-24' AND day < '2026-09-16') GROUP BY sh ORDER BY sh
```
```text
┌─sh─┬─positives─┬───tp─┬───fp─┬─prec─┬──rec─┐
│ -2 │      1950 │   73 │ 2158 │ 0.03 │ 0.04 │
│ -1 │      1976 │  138 │ 2093 │ 0.06 │ 0.07 │
│  0 │      1976 │  292 │ 1939 │ 0.13 │ 0.15 │
│  1 │      1981 │ 1579 │  652 │ 0.71 │  0.8 │
└────┴───────────┴──────┴──────┴──────┴──────┘
```

## Q9 거래량 감사: 기준 변형 (수량/7일 평균, 금액/7일 중앙값), 임계 4
```sql

WITH c AS (SELECT market, day, amount, volume, avg(amount) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) a7,
        avg(volume) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) v7,
        quantileExact(0.5)(amount) OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 7 PRECEDING AND 1 PRECEDING) med7,
        count() OVER (PARTITION BY market ORDER BY day ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING) nb FROM upbit_daily_candles FINAL WHERE day >= '2026-03-01'),
 lab AS (SELECT market, toDate(trigger_time_utc) - 1 day FROM upbit_market_event_records FINAL WHERE event_type='TRADING_VOLUME_SOARING' AND toHour(trigger_time_utc)=1 GROUP BY 1,2),
 j AS (SELECT c.*, lab.day != toDate('1970-01-01') AS pos FROM c LEFT JOIN lab USING (market, day) WHERE nb>=30 AND day >= '2026-03-24' AND day < '2026-09-16')
SELECT k, tp, fp, fn, round(tp/(tp+fp),2) prec, round(tp/(tp+fn),2) rec FROM (
 SELECT 'amount/avg7' k, countIf(amount/a7>=4 AND pos) tp, countIf(amount/a7>=4 AND NOT pos) fp, countIf(amount/a7<4 AND pos) fn FROM j
 UNION ALL SELECT 'volume/avg7', countIf(volume/v7>=4 AND pos), countIf(volume/v7>=4 AND NOT pos), countIf(volume/v7<4 AND pos) FROM j
 UNION ALL SELECT 'amount/median7', countIf(amount/med7>=4 AND pos), countIf(amount/med7>=4 AND NOT pos), countIf(amount/med7<4 AND pos) FROM j) ORDER BY k
```
```text
┌─k───────────┬───tp─┬──fp─┬──fn─┬─prec─┬─rec─┐
│ amount/avg7 │ 1579 │ 652 │ 402 │ 0.71 │ 0.8 │
└─────────────┴──────┴─────┴─────┴──────┴─────┘
┌─k──────────────┬───tp─┬───fp─┬──fn─┬─prec─┬──rec─┐
│ amount/median7 │ 1812 │ 2171 │ 169 │ 0.45 │ 0.91 │
└────────────────┴──────┴──────┴─────┴──────┴──────┘
┌─k───────────┬───tp─┬──fp─┬──fn─┬─prec─┬──rec─┐
│ volume/avg7 │ 1563 │ 583 │ 418 │ 0.73 │ 0.79 │
└─────────────┴──────┴─────┴─────┴──────┴──────┘
```

## Q10 가격 독립 검증(6개월, 업비트 1분봉): scripts/analysis/verify_price_threshold.py --report (저장된 결과 재집계)
```text
LEVEL_1 n=145 min=16.3% p10=47.4% p50=51.7% max=107.2% below45=8 45-55=104 95-105=8 ge195=0
LEVEL_2 n=21 min=80.5% p10=92.2% p50=101.2% max=118.9% below45=0 45-55=0 95-105=14 ge195=0
LEVEL_3 n=1 min=196.9% p10=196.9% p50=196.9% max=196.9% below45=0 45-55=0 95-105=0 ge195=1
LEVEL_1 2026-03 n=5 p10=44.2% p50=52.7% in45-55=3 below45=1
LEVEL_1 2026-04 n=28 p10=22.7% p50=52.4% in45-55=10 below45=6
LEVEL_1 2026-05 n=9 p10=46.5% p50=52.3% in45-55=8 below45=0
LEVEL_1 2026-06 n=23 p10=49.1% p50=52.0% in45-55=19 below45=0
LEVEL_1 2026-07 n=18 p10=48.1% p50=51.5% in45-55=16 below45=0
LEVEL_1 2026-08 n=28 p10=47.4% p50=51.5% in45-55=21 below45=1
LEVEL_1 2026-09 n=34 p10=48.9% p50=51.7% in45-55=27 below45=0
LEVEL_1 pre_0518 n=37 p10=41.5% p50=52.7% below45=7
LEVEL_1 post_0518 n=108 p10=48.3% p50=51.6% below45=1
outlier LEVEL_1 KRW-PROVE 2026-03-25 22:26:00 +44.2%
outlier LEVEL_1 KRW-KERNEL 2026-04-01 13:09:00 +21.8%
outlier LEVEL_1 KRW-ONT 2026-04-02 09:02:00 +22.7%
outlier LEVEL_1 KRW-XPL 2026-04-02 20:59:00 +41.5%
outlier LEVEL_1 KRW-EDGE 2026-04-07 01:50:00 +44.9%
outlier LEVEL_1 KRW-KAT 2026-04-23 16:57:00 +43.4%
outlier LEVEL_2 KRW-KAT 2026-04-24 09:55:00 +80.5%
outlier LEVEL_1 KRW-KAT 2026-04-25 10:03:00 +16.3%
outlier LEVEL_1 KRW-COW 2026-08-15 09:07:00 +37.6%
```

## 해석 요약
- Q1/Q2/Q10: 주의 = ±50%, 경고 = ±100%, 위험 = ±200%(6개월 1건). 46~50% 구간은 참조 시각 정의 차이(우리: 분 마지막 체결, 업비트: 종가).
- Q3: 오탐 1/3,221. Q4: 주의 에피소드 분의 91.1%가 ≥50%, 99.0%가 ≥46%(최소값 19.4%는 해제 지연).
- Q10 월별: 05월 이후 p50 51.5~52.3% 일정. 05-18 이전 이탈 7/37은 문서의 참조 변경(전일 종가→24h 전)과 일치. 이후 이탈 1/108(COW).
- Q6~Q9: 거래량은 7일 평균 ×4 + 하한 10억이 정밀도 0.79 / 재현율 0.80. 라벨을 하루 어긋나게 붙이면 0.06~0.13으로 붕괴 → 정렬 검증. 수량 기준도 동등, 중앙값 기준은 열등. 못 잡는 20%는 업비트의 창·예외 조건 차이로 추정(완전 재현 아님).
