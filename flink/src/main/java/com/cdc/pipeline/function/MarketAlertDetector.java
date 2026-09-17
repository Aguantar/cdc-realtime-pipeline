package com.cdc.pipeline.function;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.MarketAlert;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.Arrays;

/**
 * PRICE_24H — 업비트 "가격 급등락" 경보의 재현 (docs/16 §4-1, docs/22).
 *
 * 규칙: 현재가 / 24시간 전 분 종가 − 1 의 절대값이 50% 이상이면 주의(1), 100% 이상 경고(2), 200% 이상 위험(3).
 *   임계는 내가 고른 값이 아니다. 거래소가 실제로 지정한 순간의 지표값 분포(6개월 167건 독립 검증: 주의 p50 51.7%, 경고 101.2%, 위험 196.9%)에서
 *   역산했고, 반대 방향(≥50% 인 분 3,221개 중 3,220개가 지정 구간 안)으로 검증했다. 05-18 이후 월별 p50 51.5~52.3% 로 정적.
 * 출력: 등급이 바뀔 때만(전이) 1건. 체결마다 내면 LSK 처럼 임계 근처에서 하루 84건이 나온다 — 거래소도 지정/해제 전이로 표현하므로 같은 단위로 맞춘다.
 *
 * 상태: 마켓별 분 종가 링(1,500분 = 24h + 여유) + 마지막 분 + 현재 등급. 분이 비면 직전 종가로 채워(forward-fill) 참조 슬롯이 항상 값을 갖게 한다.
 *   MapState 대신 배열인 이유: 참조 조회가 O(1) 이어야 한다(피크 초당 59 체결). 체크포인트 크기 287 × 1,500 × 8B ≈ 3.4MB.
 * 늦은 이벤트 가드(docs/20): 적재 지연 > 60초인 행(백필·gap-fill)은 상태·판정 모두 건너뛴다. 재정렬 5.87%/최대 4.8초는 통과.
 * 섀도: rule_version 'v2-shadow' 로 market_alerts 에만 기록, 발송 없음. dq_rule_eval_daily 가 거래소 이력과 대조해 승격을 결정한다.
 */
public class MarketAlertDetector extends KeyedProcessFunction<String, CryptoTradeEvent, MarketAlert> {

    static final long LATE_EVENT_MS = 60_000;
    static final int RING = 1_500;            // 분 단위 슬롯
    static final int LOOKBACK = 1_440;        // 24h
    static final double[] THRESHOLDS = {0.5, 1.0, 2.0};
    static final String RULE_VERSION = "v2-shadow";

    private transient ValueState<double[]> ring;
    private transient ValueState<Long> lastMinute;
    private transient ValueState<Integer> level;
    private transient Counter lateEventsSkipped;
    private transient Counter transitions;

    @Override
    public void open(Configuration parameters) {
        ring = getRuntimeContext().getState(new ValueStateDescriptor<>("closeRing", PrimitiveArrayTypeInfo.DOUBLE_PRIMITIVE_ARRAY_TYPE_INFO));
        lastMinute = getRuntimeContext().getState(new ValueStateDescriptor<>("lastMinute", Types.LONG));
        level = getRuntimeContext().getState(new ValueStateDescriptor<>("level", Types.INT));
        lateEventsSkipped = getRuntimeContext().getMetricGroup().counter("lateEventsSkipped");
        transitions = getRuntimeContext().getMetricGroup().counter("levelTransitions");
    }

    static int idx(long minute) { return (int) Math.floorMod(minute, (long) RING); }

    static int levelOf(double absChange) {
        if (absChange >= THRESHOLDS[2]) return 3;
        if (absChange >= THRESHOLDS[1]) return 2;
        if (absChange >= THRESHOLDS[0]) return 1;
        return 0;
    }

    @Override
    public void processElement(CryptoTradeEvent e, Context ctx, Collector<MarketAlert> out) throws Exception {
        if (e.getSourceTimestamp() - e.getUpbitTimestamp() > LATE_EVENT_MS) {
            lateEventsSkipped.inc();
            return;
        }
        long m = Math.floorDiv(e.getUpbitTimestamp(), 60_000L);
        double p = e.getTradePrice();
        if (p <= 0) return;

        double[] r = ring.value();
        Long lm = lastMinute.value();
        if (r == null) {
            r = new double[RING];
            Arrays.fill(r, Double.NaN);
        }
        if (lm == null) {
            r[idx(m)] = p;
            ring.update(r);
            lastMinute.update(m);
            return;                                    // 24h 이력이 없으면 판정하지 않는다
        }
        if (m > lm) {                                  // 빈 분을 직전 종가로 채운다 (가장 최근 RING 분까지만)
            double last = r[idx(lm)];
            long from = Math.max(lm + 1, m - RING + 1);
            for (long k = from; k < m; k++) r[idx(k)] = last;
            lastMinute.update(m);
        }
        // m < lm (재정렬로 과거 분에 도착): 그 분의 종가 슬롯은 건드리지 않고 현재 등급 판정만 한다
        if (m >= lm) r[idx(m)] = p;
        ring.update(r);

        long refMinute = Math.max(m, lm) - LOOKBACK;
        double ref = r[idx(refMinute)];
        if (Double.isNaN(ref) || ref <= 0) return;     // 아직 24h 전 슬롯이 채워지지 않음

        double change = p / ref - 1.0;
        int lvl = levelOf(Math.abs(change));
        Integer prevObj = level.value();
        int prev = prevObj == null ? 0 : prevObj;
        if (lvl != prev) {
            double threshold = THRESHOLDS[Math.max(lvl, prev) - 1] * 100.0;
            out.collect(new MarketAlert("PRICE_24H", e.getMarket(), lvl, prev, e.getUpbitTimestamp(),
                    change * 100.0, threshold, ref, p, e.getTradeId(), RULE_VERSION));
            level.update(lvl);
            transitions.inc();
        }
    }
}
