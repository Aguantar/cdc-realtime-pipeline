package com.cdc.pipeline.binance;

import java.io.Serializable;

/** Binance trade 스트림 한 건 (docs/31 §3-2). recvMs 는 수집기가 붙인 수신 시각. */
public class BinanceTrade implements Serializable {
    private static final long serialVersionUID = 1L;
    public String symbol; public long tradeId; public double price; public double qty; public boolean buyerMaker;
    public long tradeMs; public long eventMs; public long recvMs;
}
