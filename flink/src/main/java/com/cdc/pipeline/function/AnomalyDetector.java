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
 * 암호화폐 이상 탐지 (v2 — 현실적 임계값)
 *
 * 임계값 설정 근거:
 *
 * 1. 업비트 이상거래 감시정책 (가상자산이용자보호법 기반)
 *    - 7가지 유형: 가장·통정매매, 허수성매매, 취소·정정과다,
 *      특정종목 매매집중, 체결관여 과다, 주문관여 과다, 시세관여 과다
 *    - 우리 파이프라인은 체결(trade) 데이터만 수신하므로
 *      체결 기반 3가지(매매집중, 체결관여, 시세관여) + 대량체결 1가지 구현
 *
 * 2. 학술 근거
 *    - "Detecting Crypto Pump-and-Dump Schemes" (2025, arXiv:2503.08692)
 *      EWMA + 변동성 기반 동적 임계값으로 펌프앤덤프 탐지
 *    - 고정 임계값이 아닌, 코인별 과거 패턴 대비 이상치를 탐지하는 접근
 *
 * 3. 실측 데이터 기반 조정
 *    - v1 임계값(VOLUME 10배, PRICE 0.5%)은 시간당 651건 알림 → 과다
 *    - DOGE 136→137원(0.73%)이 매번 PRICE_SPIKE 발동 → 호가 단위 변동
 *    - VOLUME_SURGE 74% 차지 → 10배 기준이 암호화폐 특성에 비해 낮음
 *    - 조정 후 시간당 30~50건의 의미 있는 알림 목표
 *
 * 4가지 탐지 규칙:
 *
 * 1. LARGE_TRADE: 대량 체결 감지 (업비트 일 거래대금 2조+ 기준)
 *    - BTC: 5억원, ETH: 3억원, 기타: 1억원
 *
 * 2. PRICE_SPIKE: 급격한 가격 변동 (업비트: 시세관여 과다 대응)
 *    - BTC: 2% 이상 변동 (97,000,000원 × 2% = 1,940,000원)
 *    - 기타: 3% 이상 변동
 *    - DOGE 1원 변동(0.73%)은 정상 호가 단위이므로 제외됨
 *
 * 3. VOLUME_SURGE: 거래량 급증 (업비트: 체결관여 과다 대응)
 *    - EMA 대비 50배 이상, 최소 50건 학습 후 판단
 *    - 학술 근거: EWMA 기반 동적 임계값 (arXiv:2503.08692)
 *
 * 4. RAPID_TRADES: 단기간 다수 체결 (업비트: 단주매매/매매집중 대응)
 *    - 10초 내 같은 마켓에서 100건 이상
 *    - BTC는 정상적으로 50건/10초 가능하므로 100건으로 상향
 */
public class AnomalyDetector
        extends KeyedProcessFunction<String, CryptoTradeEvent, AnomalyAlert> {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetector.class);

    // === 1. LARGE_TRADE 임계값 (업비트 일 거래대금 2조+ 기준) ===
    private static final double LARGE_TRADE_BTC = 500_000_000;    // 5억원
    private static final double LARGE_TRADE_ETH = 300_000_000;    // 3억원
    private static final double LARGE_TRADE_DEFAULT = 100_000_000; // 1억원

    // === 2. PRICE_SPIKE 임계값 (마켓별 동적 기준) ===
    private static final double PRICE_SPIKE_BTC = 0.02;           // 2%
    private static final double PRICE_SPIKE_DEFAULT = 0.03;       // 3%

    // === 3. VOLUME_SURGE 임계값 (EWMA 기반) ===
    private static final double VOLUME_SURGE_MULTIPLIER = 50.0;   // EMA 대비 50배
    private static final long VOLUME_MIN_SAMPLES = 50;            // 최소 50건 학습 후 판단

    // === 4. RAPID_TRADES 임계값 ===
    private static final int RAPID_TRADE_COUNT = 100;             // 10초 내 100건
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

        // 1. LARGE_TRADE: 대량 체결 (마켓별 동적 임계값)
        double largeThreshold = getLargeTradeThreshold(market);
        if (amount >= largeThreshold) {
            out.collect(new AnomalyAlert(
                AlertType.LARGE_TRADE, market, event.getTradeId(),
                String.format("대량 체결 감지: %.0f원 (임계값: %.0f원)", amount, largeThreshold),
                amount, largeThreshold
            ));
        }

        // 2. PRICE_SPIKE: 가격 급변 (마켓별 동적 임계값)
        Double prevPrice = lastPrice.value();
        if (prevPrice != null && prevPrice > 0) {
            double changeRate = Math.abs(price - prevPrice) / prevPrice;
            double spikeThreshold = getPriceSpikeThreshold(market);
            if (changeRate >= spikeThreshold) {
                String direction = price > prevPrice ? "급등" : "급락";
                out.collect(new AnomalyAlert(
                    AlertType.PRICE_SPIKE, market, event.getTradeId(),
                    String.format("가격 %s: %.2f → %.2f (%.2f%%)", direction, prevPrice, price, changeRate * 100),
                    changeRate * 100, spikeThreshold * 100
                ));
            }
        }
        lastPrice.update(price);

        // 3. VOLUME_SURGE: 거래량 급증 (EWMA 기반, 최소 학습량 확보 후)
        Double avg = avgVolume.value();
        Long count = volumeCount.value();
        if (avg == null) avg = 0.0;
        if (count == null) count = 0L;

        if (count >= VOLUME_MIN_SAMPLES && avg > 0 && volume >= avg * VOLUME_SURGE_MULTIPLIER) {
            out.collect(new AnomalyAlert(
                AlertType.VOLUME_SURGE, market, event.getTradeId(),
                String.format("거래량 급증: %.8f (평균: %.8f, %.1f배)", volume, avg, volume / avg),
                volume, avg * VOLUME_SURGE_MULTIPLIER
            ));
        }

        // EMA 업데이트 (지수이동평균, alpha=0.05)
        double alpha = 0.05;
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

    /**
     * 마켓별 대량 체결 임계값
     * 업비트 일 거래대금 2조원 기준, 단일 체결로 의미 있는 금액
     */
    private double getLargeTradeThreshold(String market) {
        if (market.contains("BTC")) return LARGE_TRADE_BTC;
        if (market.contains("ETH")) return LARGE_TRADE_ETH;
        return LARGE_TRADE_DEFAULT;
    }

    /**
     * 마켓별 가격 급변 임계값
     * BTC: 시가총액이 크고 유동성이 높아 2% 변동은 실제 급변
     * 기타: 알트코인은 변동성이 커서 3% 이상이 의미 있는 급변
     */
    private double getPriceSpikeThreshold(String market) {
        if (market.contains("BTC")) return PRICE_SPIKE_BTC;
        return PRICE_SPIKE_DEFAULT;
    }
}
