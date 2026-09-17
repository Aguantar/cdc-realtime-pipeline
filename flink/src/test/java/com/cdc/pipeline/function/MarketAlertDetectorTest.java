package com.cdc.pipeline.function;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.MarketAlert;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** PRICE_24H 등급 전이 검증 (docs/22). 임계 50/100/200% 는 docs/16 의 거래소 지정 이력 역산값. */
public class MarketAlertDetectorTest {

    private KeyedOneInputStreamOperatorTestHarness<String, CryptoTradeEvent, MarketAlert> h;
    private static final long T0 = 1_789_600_020_000L;   // 분 경계에 정렬 (÷60000 = 29,826,667). 정렬 안 된 T0 로는 +30초가 다음 분이 되어 '같은 분의 종가' 시나리오가 깨진다
    private static final long MIN = 60_000L;

    @Before
    public void setUp() throws Exception {
        h = new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new MarketAlertDetector()), CryptoTradeEvent::getMarket, Types.STRING);
        h.open();
    }

    @After
    public void tearDown() throws Exception { h.close(); }

    private static CryptoTradeEvent trade(long id, String market, double price, long upbitTs, long sourceTs) {
        CryptoTradeEvent e = new CryptoTradeEvent();
        e.setOp("c"); e.setTradeId(id); e.setMarket(market); e.setTradePrice(price); e.setTradeVolume(1); e.setTradeAmount(price);
        e.setAskBid("BID"); e.setUpbitTimestamp(upbitTs); e.setSequentialId(upbitTs * 10_000); e.setSourceTimestamp(sourceTs);
        e.setCdcTimestamp(sourceTs + 5); e.setCdcLatencyMs(5);
        return e;
    }
    private void live(long id, double price, long upbitTs) throws Exception {
        h.processElement(new StreamRecord<>(trade(id, "KRW-LSK", price, upbitTs, upbitTs + 1_000)));
    }
    private List<MarketAlert> out() { return h.extractOutputValues(); }

    @Test
    public void noJudgementWithoutTwentyFourHoursOfHistory() throws Exception {
        live(1, 100, T0);
        live(2, 300, T0 + 10 * MIN);
        assertEquals(0, out().size());
    }

    @Test
    public void levelTransitionsUpAndDownAreEmittedOnce() throws Exception {
        live(1, 100, T0);
        long t = T0 + 1_440 * MIN;
        live(2, 151, t);                    // +51%  → 주의
        live(3, 152, t + 1_000);            // 같은 등급 → 전이 아님
        live(4, 205, t + MIN);              // +105% → 경고
        live(5, 310, t + 2 * MIN);          // +210% → 위험
        live(6, 120, t + 3 * MIN);          // +20%  → 해제
        List<MarketAlert> a = out();
        assertEquals(4, a.size());
        assertEquals(1, a.get(0).getLevel()); assertEquals(0, a.get(0).getPrevLevel()); assertEquals(50.0, a.get(0).getThreshold(), 0.001);
        assertEquals(2, a.get(1).getLevel());
        assertEquals(3, a.get(2).getLevel()); assertEquals(200.0, a.get(2).getThreshold(), 0.001);
        assertEquals(0, a.get(3).getLevel()); assertEquals(3, a.get(3).getPrevLevel());
        assertEquals(100.0, a.get(0).getRefPrice(), 0.001);
        assertTrue(a.get(0).getValue() > 50 && a.get(0).getValue() < 52);
    }

    @Test
    public void referenceIsTheCloseOfThatMinuteNotLaterTrades() throws Exception {
        live(1, 100, T0);
        live(2, 110, T0 + 30_000);          // 같은 분의 마지막 체결 = 종가 110
        live(3, 200, T0 + 5 * MIN);
        live(4, 160, T0 + 1_440 * MIN);     // 참조 110 → +45% → 주의 아님
        assertEquals(0, out().size());
        live(5, 166, T0 + 1_440 * MIN + 1_000); // +50.9% → 주의
        assertEquals(1, out().size());
        assertEquals(110.0, out().get(0).getRefPrice(), 0.001);
    }

    @Test
    public void lateRowsDoNotTouchStateOrEmit() throws Exception {
        live(1, 100, T0);
        h.processElement(new StreamRecord<>(trade(2, "KRW-LSK", 50, T0 - 6 * 24 * 60 * MIN, T0 + 2_000)));
        live(3, 151, T0 + 1_440 * MIN);
        List<MarketAlert> a = out();
        assertEquals(1, a.size());
        assertEquals(100.0, a.get(0).getRefPrice(), 0.001);
    }

    @Test
    public void reorderWithinSecondsIsNotLate() throws Exception {
        live(1, 100, T0);
        h.processElement(new StreamRecord<>(trade(2, "KRW-LSK", 101, T0 - 4_000, T0 + 1_000)));
        live(3, 155, T0 + 1_440 * MIN);
        assertEquals(1, out().size());
    }
}
