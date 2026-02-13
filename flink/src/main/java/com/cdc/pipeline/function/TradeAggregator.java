package com.cdc.pipeline.function;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.TradeAggResult;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.util.Collector;

/**
 * 마켓별 5분 윈도우 집계
 * 
 * AggregateFunction + ProcessWindowFunction 조합:
 * - AggregateFunction: 증분 집계 (메모리 효율적, 이벤트마다 누적)
 * - ProcessWindowFunction: 윈도우 메타데이터(시작/종료 시각) 접근
 * 
 * 집계 항목:
 * - 체결 건수 (BID/ASK별)
 * - 총 금액, 총 수량
 * - 평균/최저/최고 가격
 * - VWAP (거래량 가중 평균 가격 = totalAmount / totalVolume)
 */
public class TradeAggregator
        implements AggregateFunction<CryptoTradeEvent, TradeAggregator.Accumulator, TradeAggResult> {

    public static class Accumulator implements java.io.Serializable {
        String market;
        long tradeCount = 0;
        long bidCount = 0;
        long askCount = 0;
        double totalAmount = 0;
        double totalVolume = 0;
        double priceSum = 0;
        double minPrice = Double.MAX_VALUE;
        double maxPrice = Double.MIN_VALUE;
    }

    @Override
    public Accumulator createAccumulator() {
        return new Accumulator();
    }

    @Override
    public Accumulator add(CryptoTradeEvent event, Accumulator acc) {
        acc.market = event.getMarket();
        acc.tradeCount++;

        if ("BID".equals(event.getAskBid())) {
            acc.bidCount++;
        } else {
            acc.askCount++;
        }

        acc.totalAmount += event.getTradeAmount();
        acc.totalVolume += event.getTradeVolume();
        acc.priceSum += event.getTradePrice();
        acc.minPrice = Math.min(acc.minPrice, event.getTradePrice());
        acc.maxPrice = Math.max(acc.maxPrice, event.getTradePrice());

        return acc;
    }

    @Override
    public TradeAggResult getResult(Accumulator acc) {
        TradeAggResult result = new TradeAggResult();
        result.setMarket(acc.market);
        result.setTradeCount(acc.tradeCount);
        result.setBidCount(acc.bidCount);
        result.setAskCount(acc.askCount);
        result.setTotalAmount(acc.totalAmount);
        result.setTotalVolume(acc.totalVolume);
        result.setAvgPrice(acc.tradeCount > 0 ? acc.priceSum / acc.tradeCount : 0);
        result.setMinPrice(acc.minPrice == Double.MAX_VALUE ? 0 : acc.minPrice);
        result.setMaxPrice(acc.maxPrice == Double.MIN_VALUE ? 0 : acc.maxPrice);
        result.setVwap(acc.totalVolume > 0 ? acc.totalAmount / acc.totalVolume : 0);
        return result;
    }

    @Override
    public Accumulator merge(Accumulator a, Accumulator b) {
        a.tradeCount += b.tradeCount;
        a.bidCount += b.bidCount;
        a.askCount += b.askCount;
        a.totalAmount += b.totalAmount;
        a.totalVolume += b.totalVolume;
        a.priceSum += b.priceSum;
        a.minPrice = Math.min(a.minPrice, b.minPrice);
        a.maxPrice = Math.max(a.maxPrice, b.maxPrice);
        if (a.market == null) a.market = b.market;
        return a;
    }

    /**
     * 윈도우 메타데이터를 추가하는 ProcessWindowFunction
     * aggregate()와 함께 사용: .aggregate(new TradeAggregator(), new WindowEnricher())
     */
    public static class WindowEnricher
            extends ProcessWindowFunction<TradeAggResult, TradeAggResult, String, TimeWindow> {

        @Override
        public void process(String key, Context ctx, Iterable<TradeAggResult> elements, Collector<TradeAggResult> out) {
            TradeAggResult result = elements.iterator().next();
            result.setWindowStart(ctx.window().getStart());
            result.setWindowEnd(ctx.window().getEnd());
            out.collect(result);
        }
    }
}
