package com.cdc.pipeline.sink;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.TradeAggResult;
import com.cdc.pipeline.model.AnomalyAlert;

import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

import java.sql.Timestamp;

/**
 * ClickHouse JDBC Sink 팩토리 (암호화폐 버전)
 */
public class ClickHouseSinks {

    private static final int BATCH_SIZE = 200;
    private static final long BATCH_INTERVAL_MS = 3000;
    private static final int MAX_RETRIES = 3;

    /**
     * Raw 체결 데이터 → crypto_trades 테이블
     */
    public static SinkFunction<CryptoTradeEvent> rawTradeSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO crypto_trades (op, trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, source_ts, cdc_ts, cdc_latency_ms, flink_ts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (ps, event) -> {
                ps.setString(1, event.getOp());
                ps.setLong(2, event.getTradeId());
                ps.setString(3, event.getMarket());
                ps.setDouble(4, event.getTradePrice());
                ps.setDouble(5, event.getTradeVolume());
                ps.setDouble(6, event.getTradeAmount());
                ps.setString(7, event.getAskBid());
                ps.setLong(8, event.getUpbitTimestamp());
                ps.setLong(9, event.getSequentialId());
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
     * 5분 윈도우 집계 → trade_aggregations 테이블
     */
    public static SinkFunction<TradeAggResult> aggregationSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO trade_aggregations (market, window_start, window_end, trade_count, bid_count, ask_count, total_amount, total_volume, avg_price, min_price, max_price, vwap) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (ps, agg) -> {
                ps.setString(1, agg.getMarket());
                ps.setTimestamp(2, new Timestamp(agg.getWindowStart()));
                ps.setTimestamp(3, new Timestamp(agg.getWindowEnd()));
                ps.setLong(4, agg.getTradeCount());
                ps.setLong(5, agg.getBidCount());
                ps.setLong(6, agg.getAskCount());
                ps.setDouble(7, agg.getTotalAmount());
                ps.setDouble(8, agg.getTotalVolume());
                ps.setDouble(9, agg.getAvgPrice());
                ps.setDouble(10, agg.getMinPrice());
                ps.setDouble(11, agg.getMaxPrice());
                ps.setDouble(12, agg.getVwap());
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
            "INSERT INTO anomaly_alerts (alert_type, market, trade_id, message, value, threshold, detected_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (ps, alert) -> {
                ps.setString(1, alert.getType().name());
                ps.setString(2, alert.getMarket());
                ps.setLong(3, alert.getTradeId());
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
