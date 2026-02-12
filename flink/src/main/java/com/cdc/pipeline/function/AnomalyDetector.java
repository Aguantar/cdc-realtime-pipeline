package com.cdc.pipeline.function;

import com.cdc.pipeline.model.OrderEvent;
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
 * 종목별 이상 탐지기
 * 
 * KeyedProcessFunction을 사용하는 이유:
 * - 종목(symbol)별로 독립적인 상태(state)를 유지해야 함
 * - 이전 가격을 기억하고 현재 가격과 비교해야 함
 * - 단기간 주문 횟수를 추적해야 함
 * 
 * Flink의 Keyed State는 RocksDB에 저장되므로,
 * 체크포인트 시 자동으로 영속화되어 장애 후 복구 가능
 * 
 * 탐지 규칙:
 * 1. LARGE_ORDER: 수량 > 500
 * 2. HIGH_AMOUNT: 주문 금액 > 1억원
 * 3. PRICE_SPIKE: 이전 가격 대비 5% 이상 변동
 * 4. RAPID_ORDERS: 10초 내 동일 종목 5건 이상
 */
public class AnomalyDetector extends KeyedProcessFunction<String, OrderEvent, AnomalyAlert> {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetector.class);

    // 임계값 설정
    private static final int LARGE_ORDER_THRESHOLD = 500;
    private static final double HIGH_AMOUNT_THRESHOLD = 100_000_000.0; // 1억
    private static final double PRICE_SPIKE_RATIO = 0.05; // 5%
    private static final int RAPID_ORDER_COUNT = 5;
    private static final long RAPID_ORDER_WINDOW_MS = 10_000; // 10초

    // 종목별 상태
    private transient ValueState<Double> lastPriceState;
    private transient ValueState<Long> orderCountState;
    private transient ValueState<Long> windowStartState;

    @Override
    public void open(Configuration parameters) {
        // 각 종목별로 독립적인 상태 생성
        lastPriceState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastPrice", Types.DOUBLE));
        orderCountState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("orderCount", Types.LONG));
        windowStartState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("windowStart", Types.LONG));
    }

    @Override
    public void processElement(OrderEvent event, Context ctx, Collector<AnomalyAlert> out) throws Exception {

        // 규칙 1: 대량 주문
        if (event.getQuantity() != null && event.getQuantity() > LARGE_ORDER_THRESHOLD) {
            out.collect(new AnomalyAlert(
                    AlertType.LARGE_ORDER,
                    event.getSymbol(),
                    event.getOrderId(),
                    String.format("대량 주문 감지: %d주 (임계값: %d)", event.getQuantity(), LARGE_ORDER_THRESHOLD),
                    event.getQuantity(),
                    LARGE_ORDER_THRESHOLD
            ));
        }

        // 규칙 2: 고액 주문
        double totalAmount = event.getTotalAmount();
        if (totalAmount > HIGH_AMOUNT_THRESHOLD) {
            out.collect(new AnomalyAlert(
                    AlertType.HIGH_AMOUNT,
                    event.getSymbol(),
                    event.getOrderId(),
                    String.format("고액 주문 감지: %.0f원 (임계값: %.0f)", totalAmount, HIGH_AMOUNT_THRESHOLD),
                    totalAmount,
                    HIGH_AMOUNT_THRESHOLD
            ));
        }

        // 규칙 3: 급격한 가격 변동
        Double lastPrice = lastPriceState.value();
        if (lastPrice != null && lastPrice > 0 && event.getPrice() > 0) {
            double changeRatio = Math.abs(event.getPrice() - lastPrice) / lastPrice;
            if (changeRatio > PRICE_SPIKE_RATIO) {
                out.collect(new AnomalyAlert(
                        AlertType.PRICE_SPIKE,
                        event.getSymbol(),
                        event.getOrderId(),
                        String.format("가격 급변 감지: %.0f → %.0f (변동률: %.1f%%)",
                                lastPrice, event.getPrice(), changeRatio * 100),
                        changeRatio * 100,
                        PRICE_SPIKE_RATIO * 100
                ));
            }
        }
        lastPriceState.update(event.getPrice());

        // 규칙 4: 단기간 다수 주문
        long now = System.currentTimeMillis();
        Long windowStart = windowStartState.value();
        Long orderCount = orderCountState.value();

        if (windowStart == null || (now - windowStart) > RAPID_ORDER_WINDOW_MS) {
            // 새 윈도우 시작
            windowStartState.update(now);
            orderCountState.update(1L);
        } else {
            long newCount = (orderCount != null ? orderCount : 0) + 1;
            orderCountState.update(newCount);

            if (newCount >= RAPID_ORDER_COUNT) {
                out.collect(new AnomalyAlert(
                        AlertType.RAPID_ORDERS,
                        event.getSymbol(),
                        event.getOrderId(),
                        String.format("단기 다수 주문 감지: %d초 내 %d건", 
                                RAPID_ORDER_WINDOW_MS / 1000, newCount),
                        newCount,
                        RAPID_ORDER_COUNT
                ));
                // 카운터 리셋
                windowStartState.update(now);
                orderCountState.update(0L);
            }
        }
    }
}
