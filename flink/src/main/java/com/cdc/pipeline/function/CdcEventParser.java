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
 * 방어적 파싱:
 * - null/빈 메시지 무시 (Debezium tombstone)
 * - DELETE(op=d) 이벤트 스킵 (우리 파이프라인에서 불필요)
 * - 모든 필드 null-safe 처리
 */
public class CdcEventParser implements FlatMapFunction<String, CryptoTradeEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CdcEventParser.class);
    private transient ObjectMapper mapper;

    @Override
    public void flatMap(String json, Collector<CryptoTradeEvent> out) throws Exception {
        // null/빈 메시지 방어 (Debezium tombstone 대응)
        if (json == null || json.isEmpty() || json.equals("null")) {
            return;
        }

        if (mapper == null) {
            mapper = new ObjectMapper();
        }

        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || root.isNull()) return;

            // payload가 있으면 Debezium envelope, 없으면 직접 데이터
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            if (payload == null || payload.isNull()) return;

            String op = payload.has("op") ? payload.get("op").asText() : null;
            if (op == null) return;

            // DELETE 이벤트 스킵 — MySQL cleanup의 대량 DELETE가 CDC로 오면
            // before 데이터만 있고, 우리 파이프라인에서는 불필요
            if ("d".equals(op)) {
                return;
            }

            // after 데이터 추출
            JsonNode data = payload.get("after");
            if (data == null || data.isNull()) return;

            // CDC 타임스탬프
            long cdcTs = payload.has("ts_ms") ? payload.get("ts_ms").asLong() : System.currentTimeMillis();

            // source 타임스탬프 (MySQL binlog 시각)
            long sourceTs = cdcTs;
            if (payload.has("source")) {
                JsonNode source = payload.get("source");
                if (source != null && source.has("ts_ms")) {
                    sourceTs = source.get("ts_ms").asLong();
                }
            }

            CryptoTradeEvent event = new CryptoTradeEvent();
            event.setOp(op);
            event.setTradeId(safeGetLong(data, "trade_id"));
            event.setMarket(safeGetString(data, "market", "UNKNOWN"));
            event.setTradePrice(parseDecimal(data, "trade_price"));
            event.setTradeVolume(parseDecimal(data, "trade_volume"));
            event.setTradeAmount(parseDecimal(data, "trade_amount"));
            event.setAskBid(safeGetString(data, "ask_bid", "UNKNOWN"));
            event.setUpbitTimestamp(safeGetLong(data, "upbit_timestamp"));
            event.setSequentialId(safeGetLong(data, "sequential_id"));
            event.setSourceTimestamp(sourceTs);
            event.setCdcTimestamp(cdcTs);
            event.setCdcLatencyMs(cdcTs - sourceTs);

            out.collect(event);

        } catch (Exception e) {
            LOG.warn("CDC 이벤트 파싱 실패: {}", e.getMessage());
        }
    }

    private double parseDecimal(JsonNode data, String field) {
        if (data == null || !data.has(field) || data.get(field).isNull()) return 0.0;
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

    private long safeGetLong(JsonNode data, String field) {
        if (data == null || !data.has(field) || data.get(field).isNull()) return 0L;
        return data.get(field).asLong();
    }

    private String safeGetString(JsonNode data, String field, String defaultValue) {
        if (data == null || !data.has(field) || data.get(field).isNull()) return defaultValue;
        return data.get(field).asText();
    }
}