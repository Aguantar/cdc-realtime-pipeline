package com.cdc.pipeline.function;

import com.cdc.pipeline.model.OrderEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debezium CDC JSON 메시지를 OrderEvent로 파싱
 * 
 * FlatMap을 사용하는 이유:
 * - tombstone 메시지 (value=null)는 건너뛰어야 함
 * - 파싱 실패 시 스트림을 멈추지 않고 건너뛰어야 함
 * - map()은 1:1 변환만 가능하지만, flatMap()은 0개 이상 출력 가능
 * 
 * Debezium 메시지 구조:
 * {
 *   "before": { ... },     // 변경 전 (UPDATE/DELETE)
 *   "after":  { ... },     // 변경 후 (INSERT/UPDATE/SNAPSHOT)
 *   "source": { "ts_ms": ... },
 *   "op": "c|u|d|r",
 *   "ts_ms": ...
 * }
 */
public class CdcEventParser implements FlatMapFunction<String, OrderEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CdcEventParser.class);
    private transient ObjectMapper mapper;

    @Override
    public void flatMap(String json, Collector<OrderEvent> out) {
        if (json == null || json.equals("null") || json.trim().isEmpty()) {
            return; // tombstone 메시지 무시
        }

        if (mapper == null) {
            mapper = new ObjectMapper();
        }

        try {
            JsonNode root = mapper.readTree(json);
            String op = root.path("op").asText();
            Long sourceTs = root.path("source").path("ts_ms").asLong(0);
            Long cdcTs = root.path("ts_ms").asLong(0);

            // after 또는 before에서 데이터 추출
            // INSERT(c), UPDATE(u), SNAPSHOT(r) → after 사용
            // DELETE(d) → before 사용
            JsonNode data;
            if ("d".equals(op)) {
                data = root.path("before");
            } else {
                data = root.path("after");
            }

            if (data == null || data.isMissingNode() || data.isNull()) {
                LOG.warn("No data found in CDC event: op={}", op);
                return;
            }

            OrderEvent event = new OrderEvent();
            event.setOp(op);
            event.setOrderId(data.path("order_id").asLong());
            event.setUserId(data.path("user_id").asLong());
            event.setSymbol(data.path("symbol").asText());
            event.setOrderType(data.path("order_type").asText());
            event.setQuantity(data.path("quantity").asInt());
            event.setStatus(data.path("status").asText());
            event.setSourceTimestamp(sourceTs);
            event.setCdcTimestamp(cdcTs);

            // price는 decimal.handling.mode=string이므로 문자열로 옴
            String priceStr = data.path("price").asText("0");
            event.setPrice(Double.parseDouble(priceStr));

            out.collect(event);

        } catch (Exception e) {
            LOG.error("Failed to parse CDC event: {}", 
                    json.length() > 200 ? json.substring(0, 200) + "..." : json, e);
        }
    }
}
