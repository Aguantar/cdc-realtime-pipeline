package com.cdc.pipeline.sink;

import com.cdc.pipeline.model.OrderEvent;
import com.cdc.pipeline.model.OrderAggResult;
import com.cdc.pipeline.model.AnomalyAlert;

import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

import java.sql.Timestamp;

/**
 * ClickHouse JDBC Sink 팩토리
 * 
 * 왜 JDBC Sink인가:
 * - Flink의 공식 JDBC Connector로 Exactly-Once와 At-Least-Once 지원
 * - ClickHouse JDBC 드라이버와 호환
 * - 배치 INSERT로 ClickHouse 성능 극대화 (건건 INSERT 대비 10~100배 빠름)
 * 
 * JdbcExecutionOptions 설정:
 * - batchSize: 100건 모이면 한번에 INSERT (ClickHouse는 대량 INSERT에 최적화)
 * - batchIntervalMs: 5000ms(5초) 경과하면 모인 만큼 INSERT (데이터 적을 때 지연 방지)
 * - maxRetries: 3번 재시도 (일시적 네트워크 오류 대응)
 */
public class ClickHouseSinks {

    private static final int BATCH_SIZE = 100;
    private static final long BATCH_INTERVAL_MS = 5000;
    private static final int MAX_RETRIES = 3;

    /**
     * Raw CDC 이벤트 → raw_orders 테이블
     */
    public static SinkFunction<OrderEvent> rawOrderSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO raw_orders (op, order_id, user_id, symbol, order_type, quantity, price, status, total_amount, source_ts, cdc_ts, cdc_latency_ms, flink_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (ps, event) -> {
                ps.setString(1, event.getOp());
                ps.setLong(2, event.getOrderId());
                ps.setLong(3, event.getUserId());
                ps.setString(4, event.getSymbol());
                ps.setString(5, event.getOrderType());
                ps.setInt(6, event.getQuantity());
                ps.setDouble(7, event.getPrice());
                ps.setString(8, event.getStatus());
                ps.setDouble(9, event.getTotalAmount());
                ps.setTimestamp(10, new Timestamp(event.getSourceTimestamp()));
                ps.setTimestamp(11, new Timestamp(event.getCdcTimestamp()));
                ps.setLong(12, event.getCdcLatencyMs());
                ps.setTimestamp(13, new Timestamp(System.currentTimeMillis()));
            },
            executionOptions(),
            connectionOptions(clickhouseUrl)
        );
    }

    /**
     * 5분 윈도우 집계 → order_aggregations 테이블
     */
    public static SinkFunction<OrderAggResult> aggregationSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO order_aggregations (symbol, window_start, window_end, order_count, buy_count, sell_count, total_amount, avg_price, min_price, max_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (ps, agg) -> {
                ps.setString(1, agg.getSymbol());
                ps.setTimestamp(2, new Timestamp(agg.getWindowStart()));
                ps.setTimestamp(3, new Timestamp(agg.getWindowEnd()));
                ps.setLong(4, agg.getOrderCount());
                ps.setLong(5, agg.getBuyCount());
                ps.setLong(6, agg.getSellCount());
                ps.setDouble(7, agg.getTotalAmount());
                ps.setDouble(8, agg.getAvgPrice());
                ps.setDouble(9, agg.getMinPrice());
                ps.setDouble(10, agg.getMaxPrice());
            },
            executionOptions(),
            connectionOptions(clickhouseUrl)
        );
    }

    /**
     * 이상 탐지 알림 → anomaly_alerts 테이블
     */
    public static SinkFunction<AnomalyAlert> alertSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO anomaly_alerts (alert_type, symbol, order_id, message, value, threshold, detected_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (ps, alert) -> {
                ps.setString(1, alert.getType().name());
                ps.setString(2, alert.getSymbol());
                ps.setLong(3, alert.getOrderId());
                ps.setString(4, alert.getMessage());
                ps.setDouble(5, alert.getValue());
                ps.setDouble(6, alert.getThreshold());
                ps.setTimestamp(7, new Timestamp(alert.getDetectedAt()));
            },
            executionOptions(),
            connectionOptions(clickhouseUrl)
        );
    }

    private static JdbcExecutionOptions executionOptions() {
        return JdbcExecutionOptions.builder()
                .withBatchSize(BATCH_SIZE)
                .withBatchIntervalMs(BATCH_INTERVAL_MS)
                .withMaxRetries(MAX_RETRIES)
                .build();
    }

    private static JdbcConnectionOptions connectionOptions(String url) {
        return new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(url)
                .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                .build();
    }
}
