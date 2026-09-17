package com.cdc.pipeline.sink;

import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.TradeAggResult;
import com.cdc.pipeline.model.MarketAlert;

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
            "INSERT INTO crypto_trades (op, trade_id, market, trade_price, trade_volume, trade_amount, ask_bid, upbit_timestamp, sequential_id, source_ts, cdc_ts, cdc_latency_ms, flink_ts, best_ask_price, best_ask_size, best_bid_price, best_bid_size) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                setNullableDouble(ps, 14, event.getBestAskPrice());
                setNullableDouble(ps, 15, event.getBestAskSize());
                setNullableDouble(ps, 16, event.getBestBidPrice());
                setNullableDouble(ps, 17, event.getBestBidSize());
            },
            executionOptions(),
            connectionOptions(clickhouseUrl)
        );
    }

    private static void setNullableDouble(java.sql.PreparedStatement ps, int idx, Double v) throws java.sql.SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.DOUBLE); else ps.setDouble(idx, v);
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
     * 이상탐지 v2 — 마켓 등급 전이 → market_alerts (docs/22). 섀도 기간엔 이 테이블만 쓰고 발송은 없다.
     */
    public static SinkFunction<MarketAlert> marketAlertSink(String clickhouseUrl) {
        return JdbcSink.sink(
            "INSERT INTO market_alerts (alert_type, market, level, prev_level, event_time, detected_at, value, threshold, ref_price, price, trade_id, rule_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (ps, a) -> {
                ps.setString(1, a.getAlertType());
                ps.setString(2, a.getMarket());
                ps.setInt(3, a.getLevel());
                ps.setInt(4, a.getPrevLevel());
                ps.setTimestamp(5, new Timestamp(a.getEventTime()));
                ps.setTimestamp(6, new Timestamp(a.getDetectedAt()));
                ps.setDouble(7, a.getValue());
                ps.setDouble(8, a.getThreshold());
                ps.setDouble(9, a.getRefPrice());
                ps.setDouble(10, a.getPrice());
                ps.setLong(11, a.getTradeId());
                ps.setString(12, a.getRuleVersion());
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
