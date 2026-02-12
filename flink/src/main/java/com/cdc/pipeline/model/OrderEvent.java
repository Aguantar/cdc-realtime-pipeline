package com.cdc.pipeline.model;

import java.io.Serializable;

/**
 * Debezium CDC 이벤트를 파싱한 주문 이벤트
 * 
 * op 값에 따른 의미:
 *   "r" = 초기 스냅샷 (read)
 *   "c" = INSERT (create)
 *   "u" = UPDATE
 *   "d" = DELETE
 */
public class OrderEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String op;           // 오퍼레이션 타입: r, c, u, d
    private Long orderId;
    private Long userId;
    private String symbol;       // 종목코드
    private String orderType;    // BUY / SELL
    private Integer quantity;
    private Double price;
    private String status;       // PENDING / FILLED / CANCELLED / PARTIAL
    private Long sourceTimestamp; // MySQL 변경 시각 (ms)
    private Long cdcTimestamp;    // Debezium 처리 시각 (ms)

    public OrderEvent() {}

    public OrderEvent(String op, Long orderId, Long userId, String symbol,
                      String orderType, Integer quantity, Double price, 
                      String status, Long sourceTimestamp, Long cdcTimestamp) {
        this.op = op;
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.orderType = orderType;
        this.quantity = quantity;
        this.price = price;
        this.status = status;
        this.sourceTimestamp = sourceTimestamp;
        this.cdcTimestamp = cdcTimestamp;
    }

    // Getters & Setters
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getSourceTimestamp() { return sourceTimestamp; }
    public void setSourceTimestamp(Long sourceTimestamp) { this.sourceTimestamp = sourceTimestamp; }

    public Long getCdcTimestamp() { return cdcTimestamp; }
    public void setCdcTimestamp(Long cdcTimestamp) { this.cdcTimestamp = cdcTimestamp; }

    /**
     * CDC 레이턴시 (Debezium 처리 시각 - MySQL 변경 시각)
     */
    public long getCdcLatencyMs() {
        if (sourceTimestamp != null && cdcTimestamp != null) {
            return cdcTimestamp - sourceTimestamp;
        }
        return -1;
    }

    /**
     * 주문 금액 (수량 × 가격)
     */
    public double getTotalAmount() {
        if (quantity != null && price != null) {
            return quantity * price;
        }
        return 0.0;
    }

    @Override
    public String toString() {
        return String.format("OrderEvent{op=%s, orderId=%d, symbol=%s, %s %d@%.0f, status=%s, latency=%dms}",
                op, orderId, symbol, orderType, quantity, price, status, getCdcLatencyMs());
    }
}
