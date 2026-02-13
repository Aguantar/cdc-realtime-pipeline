package com.cdc.pipeline.function;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.AnomalyAlert;
import com.cdc.pipeline.model.AnomalyAlert.AlertType;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 암호화폐 이상 탐지
 * 
 * 4가지 탐지 규칙:
 * 
 * 1. LARGE_TRADE: 대량 체결 감지
 *    - BTC: 1건에 1억원 이상
 *    - 기타: 1건에 5천만원 이상
 * 
 * 2. PRICE_SPIKE: 급격한 가격 변동
 *    - 직전 체결 대비 0.5% 이상 변동
 *    - 암호화폐는 주식보다 변동성이 크므로 임계값을 높게 설정
 * 
 * 3. VOLUME_SURGE: 거래량 급증
 *    - 1건 체결 수량이 최근 평균의 10배 이상
 *    - 이동 평균을 상태로 관리
 * 
 * 4. RAPID_TRADES: 단기간 다수 체결
 *    - 10초 내 같은 마켓에서 50건 이상
 *    - 봇 트레이딩 또는 비정상 활동 감지
 * 
 * Keyed State:
 * - 마켓별로 키가 분리되어 독립적으로 상태 관리
 * - lastPrice: 직전 가격 (PRICE_SPIKE 탐지)
 * - avgVolume: 이동 평균 수량 (VOLUME_SURGE 탐지)
 * - windowTradeCount: 10초 윈도우 체결 수 (RAPID_TRADES 탐지)
 * - windowStart: 윈도우 시작 시각 (RAPID_TRADES 탐지)
 */
public class AnomalyDetector
        extends KeyedProcessFunction<String, CryptoTradeEvent, AnomalyAlert> {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetector.class);

    // 임계값
    private static final double LARGE_TRADE_BTC = 100_000_000;    // 1억원
    private static final double LARGE_TRADE_OTHER = 50_000_000;   // 5천만원
    private static final double PRICE_SPIKE_PCT = 0.005;          // 0.5%
    private static final double VOLUME_SURGE_MULTIPLIER = 10.0;   // 평균의 10배
    private static final int RAPID_TRADE_COUNT = 50;              // 10초 내 50건
    private static final long RAPID_TRADE_WINDOW_MS = 10_000;     // 10초

    // Keyed State
    private transient ValueState<Double> lastPrice;
    private transient ValueState<Double> avgVolume;
    private transient ValueState<Long> volumeCount;
    private transient ValueState<Integer> windowTradeCount;
    private transient ValueState<Long> windowStart;

    @Override
    public void open(Configuration parameters) {
        lastPrice = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastPrice", Types.DOUBLE));
        avgVolume = getRuntimeContext().getState(
                new ValueStateDescriptor<>("avgVolume", Types.DOUBLE));
        volumeCount = getRuntimeContext().getState(
                new ValueStateDescriptor<>("volumeCount", Types.LONG));
        windowTradeCount = getRuntimeContext().getState(
                new ValueStateDescriptor<>("windowTradeCount", Types.INT));
        windowStart = getRuntimeContext().getState(
                new ValueStateDescriptor<>("windowStart", Types.LONG));
    }

    @Override
    public void processElement(CryptoTradeEvent event, Context ctx, Collector<AnomalyAlert> out) throws Exception {

        String market = event.getMarket();
        double price = event.getTradePrice();
        double volume = event.getTradeVolume();
        double amount = event.getTradeAmount();

        // 1. LARGE_TRADE: 대량 체결
        double threshold = market.contains("BTC") ? LARGE_TRADE_BTC : LARGE_TRADE_OTHER;
        if (amount >= threshold) {
            out.collect(new AnomalyAlert(
                AlertType.LARGE_TRADE, market, event.getTradeId(),
                String.format("대량 체결 감지: %.0f원 (임계값: %.0f원)", amount, threshold),
                amount, threshold
            ));
        }

        // 2. PRICE_SPIKE: 가격 급변
        Double prevPrice = lastPrice.value();
        if (prevPrice != null && prevPrice > 0) {
            double changeRate = Math.abs(price - prevPrice) / prevPrice;
            if (changeRate >= PRICE_SPIKE_PCT) {
                String direction = price > prevPrice ? "급등" : "급락";
                out.collect(new AnomalyAlert(
                    AlertType.PRICE_SPIKE, market, event.getTradeId(),
                    String.format("가격 %s: %.2f → %.2f (%.2f%%)", direction, prevPrice, price, changeRate * 100),
                    changeRate * 100, PRICE_SPIKE_PCT * 100
                ));
            }
        }
        lastPrice.update(price);

        // 3. VOLUME_SURGE: 거래량 급증
        Double avg = avgVolume.value();
        Long count = volumeCount.value();
        if (avg == null) avg = 0.0;
        if (count == null) count = 0L;

        if (count >= 20 && avg > 0 && volume >= avg * VOLUME_SURGE_MULTIPLIER) {
            out.collect(new AnomalyAlert(
                AlertType.VOLUME_SURGE, market, event.getTradeId(),
                String.format("거래량 급증: %.8f (평균: %.8f, %.1f배)", volume, avg, volume / avg),
                volume, avg * VOLUME_SURGE_MULTIPLIER
            ));
        }

        // 이동 평균 업데이트 (지수 이동 평균)
        double alpha = 0.05;  // 최근 데이터 가중치 5%
        if (count == 0) {
            avgVolume.update(volume);
        } else {
            avgVolume.update(avg * (1 - alpha) + volume * alpha);
        }
        volumeCount.update(count + 1);

        // 4. RAPID_TRADES: 단기간 다수 체결
        Long winStart = windowStart.value();
        Integer winCount = windowTradeCount.value();
        long now = System.currentTimeMillis();

        if (winStart == null || (now - winStart) > RAPID_TRADE_WINDOW_MS) {
            // 새 윈도우 시작
            windowStart.update(now);
            windowTradeCount.update(1);
        } else {
            int newCount = (winCount != null ? winCount : 0) + 1;
            windowTradeCount.update(newCount);

            if (newCount == RAPID_TRADE_COUNT) {
                out.collect(new AnomalyAlert(
                    AlertType.RAPID_TRADES, market, event.getTradeId(),
                    String.format("단기 다수 체결: 10초 내 %d건", newCount),
                    newCount, RAPID_TRADE_COUNT
                ));
            }
        }
    }
}
