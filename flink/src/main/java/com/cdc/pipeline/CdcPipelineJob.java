package com.cdc.pipeline;

import com.cdc.pipeline.function.AnomalyDetector;
import com.cdc.pipeline.function.TradeAggregator;
import com.cdc.pipeline.function.CdcEventParser;
import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.TradeAggResult;
import com.cdc.pipeline.model.AnomalyAlert;
import com.cdc.pipeline.sink.ClickHouseSinks;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDC Realtime Pipeline - 암호화폐 체결 데이터
 * 
 * Kafka CDC 토픽(cdc.crypto_db.crypto_trades)에서 Debezium 이벤트를 읽어:
 * 1. Raw 체결 이벤트 → ClickHouse crypto_trades
 * 2. 마켓별 5분 윈도우 집계 → ClickHouse trade_aggregations
 * 3. 이상 탐지 → ClickHouse anomaly_alerts
 */
public class CdcPipelineJob {

    private static final Logger LOG = LoggerFactory.getLogger(CdcPipelineJob.class);

    public static void main(String[] args) throws Exception {

        // 1. 실행 환경 설정
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);

        // 2. 환경변수에서 설정 읽기
        String bootstrapServers = System.getenv().getOrDefault(
            "KAFKA_BOOTSTRAP_SERVERS",
            "kafka-1:29092,kafka-2:29093,kafka-3:29094"
        );
        String clickhouseUrl = System.getenv().getOrDefault(
            "CLICKHOUSE_URL",
            "jdbc:clickhouse://clickhouse:8123/cdc_pipeline"
        );

        // 3. Kafka Source 설정
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics("cdc.crypto_db.crypto_trades")
                .setGroupId("flink-cdc-consumer")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // 4. Source → CryptoTradeEvent 파싱
        DataStream<CryptoTradeEvent> tradeEvents = env
                .fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "Kafka CDC Source")
                .flatMap(new CdcEventParser())
                .name("CDC Event Parser");

        // 5. Stream 1: 마켓별 5분 윈도우 집계 → ClickHouse
        DataStream<TradeAggResult> aggregated = tradeEvents
                .filter(event -> event.getOp() != null)
                .keyBy(CryptoTradeEvent::getMarket)
                .window(TumblingProcessingTimeWindows.of(Time.minutes(5)))
                .aggregate(new TradeAggregator(), new TradeAggregator.WindowEnricher())
                .name("5min Window Aggregation");

        aggregated.print("AGG");
        aggregated.addSink(ClickHouseSinks.aggregationSink(clickhouseUrl))
                .name("ClickHouse Aggregation Sink");

        // 6. Stream 2: 이상 탐지 → ClickHouse
        DataStream<AnomalyAlert> anomalies = tradeEvents
                .filter(event -> "c".equals(event.getOp()))
                .keyBy(CryptoTradeEvent::getMarket)
                .process(new AnomalyDetector())
                .name("Anomaly Detector");

        anomalies.print("ALERT");
        anomalies.addSink(ClickHouseSinks.alertSink(clickhouseUrl))
                .name("ClickHouse Alert Sink");

        // 7. Stream 3: Raw 체결 이벤트 → ClickHouse
        tradeEvents.addSink(ClickHouseSinks.rawTradeSink(clickhouseUrl))
                .name("ClickHouse Raw Trade Sink");

        LOG.info("=== CDC Crypto Pipeline Started ===");
        LOG.info("Kafka: {}", bootstrapServers);
        LOG.info("ClickHouse: {}", clickhouseUrl);
        LOG.info("Topic: cdc.crypto_db.crypto_trades");
        LOG.info("Parallelism: {}", env.getParallelism());
        LOG.info("Window: 5 minutes (tumbling)");

        env.execute("CDC Realtime Pipeline");
    }
}
