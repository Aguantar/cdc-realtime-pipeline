package com.cdc.pipeline.function;

import com.cdc.pipeline.model.AnomalyAlert;
import com.cdc.pipeline.model.CryptoTradeEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 늦은 이벤트 가드 검증 (docs/20).
 * 시나리오는 2026-09-16 실제 오염 사례(KRW-LIT)를 그대로 옮긴 것:
 *   실시간 5,915 → 백필된 옛 체결 5,515(6일 늦음) → 실시간 5,910.
 * 가드가 없으면 "급락 6.76%" + "급등 7.16%" 두 건이 난다. 가드가 있으면 0건이어야 하고,
 * 이후 실제 급변(5,910 → 6,200, 4.9%)은 정상적으로 잡혀야 한다(가드가 탐지 자체를 죽이지 않았다는 증거).
 */
public class AnomalyDetectorLateEventTest {

    private KeyedOneInputStreamOperatorTestHarness<String, CryptoTradeEvent, AnomalyAlert> harness;
    private static final long T0 = 1_789_600_000_000L; // 2026-09-16 무렵 (ms)
    private static final long DAY = 86_400_000L;

    @Before
    public void setUp() throws Exception {
        harness = new KeyedOneInputStreamOperatorTestHarness<>(
                new KeyedProcessOperator<>(new AnomalyDetector()),
                CryptoTradeEvent::getMarket,
                Types.STRING);
        harness.open();
    }

    @After
    public void tearDown() throws Exception {
        harness.close();
    }

    /** 실시간 행: 체결 후 1초 뒤 적재. */
    private static CryptoTradeEvent live(long tradeId, double price, long upbitTs) {
        return event(tradeId, price, upbitTs, upbitTs + 1_000);
    }

    /** 수리 행: 체결은 옛날, 적재는 지금. */
    private static CryptoTradeEvent repaired(long tradeId, double price, long upbitTs, long sourceTs) {
        return event(tradeId, price, upbitTs, sourceTs);
    }

    private static CryptoTradeEvent event(long tradeId, double price, long upbitTs, long sourceTs) {
        CryptoTradeEvent e = new CryptoTradeEvent();
        e.setOp("c");
        e.setTradeId(tradeId);
        e.setMarket("KRW-LIT");
        e.setTradePrice(price);
        e.setTradeVolume(1.0);
        e.setTradeAmount(price);
        e.setAskBid("BID");
        e.setUpbitTimestamp(upbitTs);
        e.setSequentialId(upbitTs * 10_000);
        e.setSourceTimestamp(sourceTs);
        e.setCdcTimestamp(sourceTs + 5);
        e.setCdcLatencyMs(5);
        return e;
    }

    private List<AnomalyAlert> priceSpikes() {
        return harness.extractOutputValues().stream()
                .filter(a -> a.getType() == AnomalyAlert.AlertType.PRICE_SPIKE)
                .collect(Collectors.toList());
    }

    @Test
    public void repairedRowDoesNotPoisonStateOrAlert() throws Exception {
        harness.processElement(new StreamRecord<>(live(1, 5_915, T0)));
        // 6일 전 체결이 지금 적재됨 (백필). 가드 없이는 5,915 → 5,515 = 6.76% 급락 오탐
        harness.processElement(new StreamRecord<>(repaired(2, 5_515, T0 - 6 * DAY, T0 + 2_000)));
        // 다음 실시간 체결. 가드 없이는 5,515 → 5,910 = 7.16% 급등 오탐(되튐)
        harness.processElement(new StreamRecord<>(live(3, 5_910, T0 + 3_000)));

        assertEquals("수리 행은 알림도 상태 갱신도 만들면 안 된다", 0, priceSpikes().size());
    }

    @Test
    public void genuineSpikeStillDetectedAfterRepairedRow() throws Exception {
        harness.processElement(new StreamRecord<>(live(1, 5_915, T0)));
        harness.processElement(new StreamRecord<>(repaired(2, 5_515, T0 - 6 * DAY, T0 + 2_000)));
        harness.processElement(new StreamRecord<>(live(3, 5_910, T0 + 3_000)));
        // 실제 급변: 5,910 → 6,200 = 4.9% (기본 임계 3%)
        harness.processElement(new StreamRecord<>(live(4, 6_200, T0 + 4_000)));

        List<AnomalyAlert> spikes = priceSpikes();
        assertEquals("실제 급변 1건만 잡혀야 한다", 1, spikes.size());
        assertTrue("직전 실시간가 5,910 기준이어야 한다(옛 5,515 기준이면 12.4%)",
                spikes.get(0).getMessage().contains("5,910"));
    }

    @Test
    public void liveReorderWithinSecondsIsNotTreatedAsLate() throws Exception {
        // 3파티션 재정렬(실측 최대 4.8초): 이벤트 시각은 과거지만 적재 지연은 정상 범위 → 가드에 걸리면 안 된다
        harness.processElement(new StreamRecord<>(live(1, 100, T0)));
        harness.processElement(new StreamRecord<>(event(2, 104, T0 - 4_000, T0 + 1_000))); // 4초 역순, 지연 5초
        assertEquals("정상 재정렬은 탐지 대상 (4% 변동 → 알림 1건)", 1, priceSpikes().size());
    }
}
