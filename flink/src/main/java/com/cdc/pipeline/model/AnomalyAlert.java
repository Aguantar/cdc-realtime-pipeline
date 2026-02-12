package com.cdc.pipeline.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 이상 탐지 알림
 */
public class AnomalyAlert implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    public enum AlertType {
        LARGE_ORDER,        // 대량 주문 (수량 > 임계값)
        HIGH_AMOUNT,        // 고액 주문 (금액 > 임계값)
        PRICE_SPIKE,        // 급격한 가격 변동
        RAPID_ORDERS        // 단기간 다수 주문
    }

    private AlertType type;
    private String symbol;
    private Long orderId;
    private String message;
    private double value;         // 탐지된 값
    private double threshold;     // 임계값
    private long detectedAt;      // 탐지 시각 (ms)

    public AnomalyAlert() {}

    public AnomalyAlert(AlertType type, String symbol, Long orderId,
                         String message, double value, double threshold) {
        this.type = type;
        this.symbol = symbol;
        this.orderId = orderId;
        this.message = message;
        this.value = value;
        this.threshold = threshold;
        this.detectedAt = System.currentTimeMillis();
    }

    // Getters & Setters
    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

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
        return String.format("ALERT[%s] %s orderId=%d: %s (value=%.0f, threshold=%.0f) at %s",
                type, symbol, orderId, message, value, threshold,
                FMT.format(Instant.ofEpochMilli(detectedAt)));
    }
}
