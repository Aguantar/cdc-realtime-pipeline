package com.cdc.pipeline;

import com.cdc.pipeline.function.AnomalyDetector;
import com.cdc.pipeline.function.OrderAggregator;
import com.cdc.pipeline.function.CdcEventParser;
import com.cdc.pipeline.model.OrderEvent;
import com.cdc.pipeline.model.OrderAggResult;
import com.cdc.pipeline.model.AnomalyAlert;

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
 * CDC Pipeline Flink Job
 * 
 * Kafka CDC 토픽에서 Debezium 이벤트를 읽어:
 * 1. Raw 이벤트를 파싱하여 OrderEvent로 변환
 * 2. 종목별 5분 윈도우 집계 (주문 건수, 총 금액, 평균 가격)
 * 3. 이상 탐지 (대량 주문, 급격한 가격 변동)
 * 
 * 현재: stdout 출력 (Phase 4에서 ClickHouse Sink 추가)
 */
public class CdcPipelineJob {

    private static final Logger LOG = LoggerFactory.getLogger(CdcPipelineJob.class);

    public static void main(String[] args) throws Exception {

        // 1. 실행 환경 설정
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);

        // 2. Kafka Source 설정
        String bootstrapServers = System.getenv().getOrDefault(
            "KAFKA_BOOTSTRAP_SERVERS", 
            "kafka-1:29092,kafka-2:29093,kafka-3:29094"
        );

        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics("cdc.orders_db.orders")
                .setGroupId("flink-cdc-consumer")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // 3. Source → OrderEvent 파싱
        DataStream<OrderEvent> orderEvents = env
                .fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "Kafka CDC Source")
                .flatMap(new CdcEventParser())
                .name("CDC Event Parser");

        // 4. Stream 1: 종목별 5분 윈도우 집계
        DataStream<OrderAggResult> aggregated = orderEvents
                .filter(event -> event.getOp() != null)
                .keyBy(OrderEvent::getSymbol)
                .window(TumblingProcessingTimeWindows.of(Time.minutes(5)))
                .aggregate(new OrderAggregator())
                .name("5min Window Aggregation");

        aggregated.print("AGG");

        // 5. Stream 2: 이상 탐지
        DataStream<AnomalyAlert> anomalies = orderEvents
                .filter(event -> "c".equals(event.getOp()) || "u".equals(event.getOp()))
                .keyBy(OrderEvent::getSymbol)
                .process(new AnomalyDetector())
                .name("Anomaly Detector");

        anomalies.print("ALERT");

        // 6. Stream 3: Raw 이벤트 (ClickHouse용 - Phase 4에서 Sink 추가)
        orderEvents.print("RAW");

        LOG.info("=== CDC Pipeline Flink Job Started ===");
        LOG.info("Kafka: {}", bootstrapServers);
        LOG.info("Topic: cdc.orders_db.orders");
        LOG.info("Parallelism: {}", env.getParallelism());
        LOG.info("Window: 5 minutes (tumbling)");

        env.execute("CDC Realtime Pipeline");
    }
}
