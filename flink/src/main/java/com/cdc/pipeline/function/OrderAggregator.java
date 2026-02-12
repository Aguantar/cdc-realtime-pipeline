package com.cdc.pipeline.function;

import com.cdc.pipeline.model.OrderEvent;
import com.cdc.pipeline.model.OrderAggResult;

import org.apache.flink.api.common.functions.AggregateFunction;

/**
 * 종목별 5분 윈도우 집계
 * 
 * AggregateFunction<IN, ACC, OUT>:
 *   IN  = OrderEvent (입력 이벤트)
 *   ACC = OrderAggResult (누적기 - 중간 상태)
 *   OUT = OrderAggResult (최종 출력)
 * 
 * Flink의 AggregateFunction은 incremental 방식으로 동작한다.
 * 이벤트가 도착할 때마다 add()로 누적하고,
 * 윈도우가 닫힐 때 getResult()로 최종 결과를 출력한다.
 * 
 * 이 방식의 장점:
 * - 윈도우 내 모든 이벤트를 메모리에 저장하지 않음 (메모리 효율)
 * - 16GB 제약 환경에서 중요한 최적화 포인트
 */
public class OrderAggregator implements AggregateFunction<OrderEvent, OrderAggResult, OrderAggResult> {

    @Override
    public OrderAggResult createAccumulator() {
        OrderAggResult acc = new OrderAggResult();
        acc.setOrderCount(0);
        acc.setBuyCount(0);
        acc.setSellCount(0);
        acc.setTotalAmount(0.0);
        acc.setAvgPrice(0.0);
        acc.setMinPrice(Double.MAX_VALUE);
        acc.setMaxPrice(Double.MIN_VALUE);
        acc.setWindowStart(System.currentTimeMillis());
        return acc;
    }

    @Override
    public OrderAggResult add(OrderEvent event, OrderAggResult acc) {
        // 종목코드 설정 (첫 이벤트에서)
        if (acc.getSymbol() == null) {
            acc.setSymbol(event.getSymbol());
        }

        // 건수 누적
        acc.setOrderCount(acc.getOrderCount() + 1);
        if ("BUY".equals(event.getOrderType())) {
            acc.setBuyCount(acc.getBuyCount() + 1);
        } else if ("SELL".equals(event.getOrderType())) {
            acc.setSellCount(acc.getSellCount() + 1);
        }

        // 금액 누적
        double amount = event.getTotalAmount();
        acc.setTotalAmount(acc.getTotalAmount() + amount);

        // 가격 통계
        double price = event.getPrice();
        if (price < acc.getMinPrice()) {
            acc.setMinPrice(price);
        }
        if (price > acc.getMaxPrice()) {
            acc.setMaxPrice(price);
        }

        // avgPrice 필드를 가격 합산용으로 임시 사용 (getResult에서 평균으로 변환)
        acc.setAvgPrice(acc.getAvgPrice() + price);

        return acc;
    }

    @Override
    public OrderAggResult getResult(OrderAggResult acc) {
        acc.setWindowEnd(System.currentTimeMillis());

        // avgPrice: 누적된 가격 합을 건수로 나누어 평균 계산
        if (acc.getOrderCount() > 0) {
            acc.setAvgPrice(acc.getAvgPrice() / acc.getOrderCount());
        }

        // 빈 윈도우 처리
        if (acc.getMinPrice() == Double.MAX_VALUE) {
            acc.setMinPrice(0.0);
        }
        if (acc.getMaxPrice() == Double.MIN_VALUE) {
            acc.setMaxPrice(0.0);
        }

        return acc;
    }

    @Override
    public OrderAggResult merge(OrderAggResult a, OrderAggResult b) {
        OrderAggResult merged = new OrderAggResult();
        merged.setSymbol(a.getSymbol() != null ? a.getSymbol() : b.getSymbol());
        merged.setOrderCount(a.getOrderCount() + b.getOrderCount());
        merged.setBuyCount(a.getBuyCount() + b.getBuyCount());
        merged.setSellCount(a.getSellCount() + b.getSellCount());
        merged.setTotalAmount(a.getTotalAmount() + b.getTotalAmount());
        // merge 시 avgPrice는 가격 합산 상태이므로 그대로 합산
        merged.setAvgPrice(a.getAvgPrice() + b.getAvgPrice());
        merged.setMinPrice(Math.min(a.getMinPrice(), b.getMinPrice()));
        merged.setMaxPrice(Math.max(a.getMaxPrice(), b.getMaxPrice()));
        merged.setWindowStart(Math.min(a.getWindowStart(), b.getWindowStart()));
        merged.setWindowEnd(Math.max(a.getWindowEnd(), b.getWindowEnd()));
        return merged;
    }
}
