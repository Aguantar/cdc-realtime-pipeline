package com.cdc.pipeline.model;

import java.io.Serializable;

/**
 * 암호화폐 체결 이벤트 모델
 * 
 * Debezium CDC 이벤트를 파싱하여 생성한다.
 * Upbit 실시간 체결 데이터의 핵심 필드를 담고 있다.
 */
public class CryptoTradeEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private String op;              // CDC 오퍼레이션: r(snapshot), c(insert), u(update), d(delete)
    private long tradeId;           // MySQL AUTO_INCREMENT PK
    private String market;          // 마켓 코드 (KRW-BTC, KRW-ETH 등)
    private double tradePrice;      // 체결 가격 (KRW)
    private double tradeVolume;     // 체결 수량
    private double tradeAmount;     // 체결 금액 (price × volume)
    private String askBid;          // ASK(매도) / BID(매수)
    private long upbitTimestamp;    // Upbit 체결 시각 (Unix ms)
    private long sequentialId;      // Upbit 체결 고유 ID
    private long sourceTimestamp;   // MySQL INSERT 시각 (ms)
    private long cdcTimestamp;      // Debezium 처리 시각 (ms)
    private long cdcLatencyMs;      // CDC 레이턴시 (cdcTimestamp - sourceTimestamp)

    // --- Getters & Setters ---

    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }

    public long getTradeId() { return tradeId; }
    public void setTradeId(long tradeId) { this.tradeId = tradeId; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public double getTradePrice() { return tradePrice; }
    public void setTradePrice(double tradePrice) { this.tradePrice = tradePrice; }

    public double getTradeVolume() { return tradeVolume; }
    public void setTradeVolume(double tradeVolume) { this.tradeVolume = tradeVolume; }

    public double getTradeAmount() { return tradeAmount; }
    public void setTradeAmount(double tradeAmount) { this.tradeAmount = tradeAmount; }

    public String getAskBid() { return askBid; }
    public void setAskBid(String askBid) { this.askBid = askBid; }

    public long getUpbitTimestamp() { return upbitTimestamp; }
    public void setUpbitTimestamp(long upbitTimestamp) { this.upbitTimestamp = upbitTimestamp; }

    public long getSequentialId() { return sequentialId; }
    public void setSequentialId(long sequentialId) { this.sequentialId = sequentialId; }

    public long getSourceTimestamp() { return sourceTimestamp; }
    public void setSourceTimestamp(long sourceTimestamp) { this.sourceTimestamp = sourceTimestamp; }

    public long getCdcTimestamp() { return cdcTimestamp; }
    public void setCdcTimestamp(long cdcTimestamp) { this.cdcTimestamp = cdcTimestamp; }

    public long getCdcLatencyMs() { return cdcLatencyMs; }
    public void setCdcLatencyMs(long cdcLatencyMs) { this.cdcLatencyMs = cdcLatencyMs; }

    @Override
    public String toString() {
        return String.format(
            "CryptoTrade{op=%s, id=%d, %s, %s %.8f@%.0f=%.0fKRW, latency=%dms}",
            op, tradeId, market, askBid, tradeVolume, tradePrice, tradeAmount, cdcLatencyMs
        );
    }
}
