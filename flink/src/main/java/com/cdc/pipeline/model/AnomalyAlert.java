package com.cdc.pipeline.model;

import java.io.Serializable;

/**
 * 이상 탐지 알림
 */
public class AnomalyAlert implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum AlertType {
        LARGE_TRADE,        // 대량 체결 (금액 기준)
        PRICE_SPIKE,        // 급격한 가격 변동
        VOLUME_SURGE,       // 거래량 급증
        RAPID_TRADES        // 단기간 다수 체결
    }

    private AlertType type;
    private String market;
    private long tradeId;
    private String message;
    private double value;
    private double threshold;
    private long detectedAt;

    public AnomalyAlert() {}

    public AnomalyAlert(AlertType type, String market, long tradeId,
                         String message, double value, double threshold) {
        this.type = type;
        this.market = market;
        this.tradeId = tradeId;
        this.message = message;
        this.value = value;
        this.threshold = threshold;
        this.detectedAt = System.currentTimeMillis();
    }

    // --- Getters & Setters ---

    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public long getTradeId() { return tradeId; }
    public void setTradeId(long tradeId) { this.tradeId = tradeId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public long getDetectedAt() { return detectedAt; }
    public void setDetectedAt(long detectedAt) { this.detectedAt = detectedAt; }

    @Override
    public String toString() {
        return String.format("ALERT[%s] %s tradeId=%d: %s", type, market, tradeId, message);
    }
}
