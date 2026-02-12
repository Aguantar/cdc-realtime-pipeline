package com.cdc.pipeline.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 종목별 5분 윈도우 집계 결과
 */
public class OrderAggResult implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    private String symbol;
    private long orderCount;      // 주문 건수
    private long buyCount;        // 매수 건수
    private long sellCount;       // 매도 건수
    private double totalAmount;   // 총 주문 금액
    private double avgPrice;      // 평균 가격
    private double minPrice;      // 최저 가격
    private double maxPrice;      // 최고 가격
    private long windowStart;     // 윈도우 시작 시각 (ms)
    private long windowEnd;       // 윈도우 종료 시각 (ms)

    public OrderAggResult() {}

    // Getters & Setters
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public long getOrderCount() { return orderCount; }
    public void setOrderCount(long orderCount) { this.orderCount = orderCount; }

    public long getBuyCount() { return buyCount; }
    public void setBuyCount(long buyCount) { this.buyCount = buyCount; }

    public long getSellCount() { return sellCount; }
    public void setSellCount(long sellCount) { this.sellCount = sellCount; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    public double getAvgPrice() { return avgPrice; }
    public void setAvgPrice(double avgPrice) { this.avgPrice = avgPrice; }

    public double getMinPrice() { return minPrice; }
    public void setMinPrice(double minPrice) { this.minPrice = minPrice; }

    public double getMaxPrice() { return maxPrice; }
    public void setMaxPrice(double maxPrice) { this.maxPrice = maxPrice; }

    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }

    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long windowEnd) { this.windowEnd = windowEnd; }

    @Override
    public String toString() {
        return String.format(
            "Agg{symbol=%s, window=[%s~%s], orders=%d(B:%d/S:%d), total=%.0f, avg=%.0f, range=[%.0f~%.0f]}",
            symbol,
            FMT.format(Instant.ofEpochMilli(windowStart)),
            FMT.format(Instant.ofEpochMilli(windowEnd)),
            orderCount, buyCount, sellCount,
            totalAmount, avgPrice, minPrice, maxPrice
        );
    }
}
