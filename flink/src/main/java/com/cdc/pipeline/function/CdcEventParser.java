package com.cdc.pipeline.function;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debezium CDC JSON → CryptoTradeEvent 변환
 * 
 * Debezium이 생성하는 JSON 구조:
 * {
 *   "payload": {
 *     "before": null,
 *     "after": {
 *       "trade_id": 1,
 *       "market": "KRW-BTC",
 *       "trade_price": "97000000.00000000",
 *       "trade_volume": "0.00100000",
 *       "trade_amount": "97000.0000",
 *       "ask_bid": "BID",
 *       "upbit_timestamp": 1770955800000,
 *       "sequential_id": 17709558000000001,
 *       "created_at": "2026-02-13T06:20:28Z"
 *     },
 *     "op": "c",
 *     "ts_ms": 1770955828123
 *   }
 * }
 * 
 * decimal.handling.mode=string이므로 DECIMAL 필드는 문자열로 온다.
 */
public class CdcEventParser implements FlatMapFunction<String, CryptoTradeEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CdcEventParser.class);
    private transient ObjectMapper mapper;

    @Override
    public void flatMap(String json, Collector<CryptoTradeEvent> out) throws Exception {
        if (mapper == null) {
            mapper = new ObjectMapper();
        }

        try {
            JsonNode root = mapper.readTree(json);

            // payload가 있으면 Debezium envelope, 없으면 직접 데이터
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            String op = payload.has("op") ? payload.get("op").asText() : null;
            if (op == null) return;

            // delete 이벤트는 before에서, 나머지는 after에서
            JsonNode data;
            if ("d".equals(op)) {
                data = payload.get("before");
            } else {
                data = payload.get("after");
            }
            if (data == null || data.isNull()) return;

            // CDC 타임스탬프
            long cdcTs = payload.has("ts_ms") ? payload.get("ts_ms").asLong() : System.currentTimeMillis();

            // source 타임스탬프 (MySQL binlog 시각)
            long sourceTs = cdcTs;
            if (payload.has("source") && payload.get("source").has("ts_ms")) {
                sourceTs = payload.get("source").get("ts_ms").asLong();
            }

            CryptoTradeEvent event = new CryptoTradeEvent();
            event.setOp(op);
            event.setTradeId(data.has("trade_id") ? data.get("trade_id").asLong() : 0);
            event.setMarket(data.has("market") ? data.get("market").asText() : "UNKNOWN");

            // DECIMAL 필드는 문자열로 옴 (decimal.handling.mode=string)
            event.setTradePrice(parseDecimal(data, "trade_price"));
            event.setTradeVolume(parseDecimal(data, "trade_volume"));
            event.setTradeAmount(parseDecimal(data, "trade_amount"));

            event.setAskBid(data.has("ask_bid") ? data.get("ask_bid").asText() : "UNKNOWN");
            event.setUpbitTimestamp(data.has("upbit_timestamp") ? data.get("upbit_timestamp").asLong() : 0);
            event.setSequentialId(data.has("sequential_id") ? data.get("sequential_id").asLong() : 0);

            event.setSourceTimestamp(sourceTs);
            event.setCdcTimestamp(cdcTs);
            event.setCdcLatencyMs(cdcTs - sourceTs);

            out.collect(event);

        } catch (Exception e) {
            LOG.warn("CDC 이벤트 파싱 실패: {}", e.getMessage());
        }
    }

    /**
     * DECIMAL 필드 파싱.
     * decimal.handling.mode=string이면 "97000000.00000000" 형태.
     * 숫자로 올 수도 있으므로 둘 다 처리.
     */
    private double parseDecimal(JsonNode data, String field) {
        if (!data.has(field) || data.get(field).isNull()) return 0.0;
        JsonNode node = data.get(field);
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return node.asDouble();
    }
}
