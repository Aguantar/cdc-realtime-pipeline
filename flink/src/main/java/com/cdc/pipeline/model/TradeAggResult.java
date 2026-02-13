package com.cdc.pipeline.model;

import java.io.Serializable;

/**
 * 마켓별 5분 윈도우 집계 결과
 */
public class TradeAggResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String market;          // KRW-BTC, KRW-ETH 등
    private long windowStart;
    private long windowEnd;
    private long tradeCount;        // 체결 건수
    private long bidCount;          // 매수 건수
    private long askCount;          // 매도 건수
    private double totalAmount;     // 총 체결 금액 (KRW)
    private double totalVolume;     // 총 체결 수량
    private double avgPrice;        // 평균 가격
    private double minPrice;        // 최저 가격
    private double maxPrice;        // 최고 가격
    private double vwap;            // 거래량 가중 평균 가격 (totalAmount / totalVolume)

    // --- Getters & Setters ---

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public long getWindowStart() { return windowStart; }
    public void setWindowStart(long windowStart) { this.windowStart = windowStart; }

    public long getWindowEnd() { return windowEnd; }
    public void setWindowEnd(long windowEnd) { this.windowEnd = windowEnd; }

    public long getTradeCount() { return tradeCount; }
    public void setTradeCount(long tradeCount) { this.tradeCount = tradeCount; }

    public long getBidCount() { return bidCount; }
    public void setBidCount(long bidCount) { this.bidCount = bidCount; }

    public long getAskCount() { return askCount; }
    public void setAskCount(long askCount) { this.askCount = askCount; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    public double getTotalVolume() { return totalVolume; }
    public void setTotalVolume(double totalVolume) { this.totalVolume = totalVolume; }

    public double getAvgPrice() { return avgPrice; }
    public void setAvgPrice(double avgPrice) { this.avgPrice = avgPrice; }

    public double getMinPrice() { return minPrice; }
    public void setMinPrice(double minPrice) { this.minPrice = minPrice; }

    public double getMaxPrice() { return maxPrice; }
    public void setMaxPrice(double maxPrice) { this.maxPrice = maxPrice; }

    public double getVwap() { return vwap; }
    public void setVwap(double vwap) { this.vwap = vwap; }

    @Override
    public String toString() {
        return String.format(
            "AGG{%s, window=[%tT~%tT], trades=%d(B:%d/S:%d), amount=%.0fKRW, vwap=%.2f, range=[%.2f~%.2f]}",
            market, windowStart, windowEnd,
            tradeCount, bidCount, askCount,
            totalAmount, vwap, minPrice, maxPrice
        );
    }
}
