package com.cdc.pipeline;

import com.cdc.pipeline.function.MarketAlertDetector;
import com.cdc.pipeline.function.TradeAggregator;
import com.cdc.pipeline.function.CdcEventParser;
import com.cdc.pipeline.model.CryptoTradeEvent;
import com.cdc.pipeline.model.TradeAggResult;
import com.cdc.pipeline.model.MarketAlert;
import com.cdc.pipeline.sink.ClickHouseSinks;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import com.cdc.pipeline.function.NullSafeStringSchema;
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
        // 2026-09-09: 클러스터 기본(3회×10초)은 ClickHouse 재시작(실측 24초)보다 짧아 잡이 FAILED로 멈출 수 있음 → 20회×30초
        env.setRestartStrategy(org.apache.flink.api.common.restartstrategy.RestartStrategies.fixedDelayRestart(
                20, org.apache.flink.api.common.time.Time.seconds(30)));

        // 2. 환경변수에서 설정 읽기
        String bootstrapServers = System.getenv().getOrDefault(
            "KAFKA_BOOTSTRAP_SERVERS",
            "kafka-1:29092,kafka-2:29093,kafka-3:29094"
        );
        String clickhouseUrl = System.getenv().getOrDefault(
            "CLICKHOUSE_URL",
            "jdbc:clickhouse://clickhouse:8123/cdc_pipeline"
        );
        // 2026-09-17 부하 실험(docs/15) 격리용. 기본값은 프로덕션과 동일하고, 실험 잡만 제출 시 env 로 바꾼다:
        //   CDC_TOPIC=load_test.trades CDC_GROUP_ID=flink-loadtest-consumer CLICKHOUSE_TABLE_PREFIX=load_test_
        //   MARKET_ALERTS_ENABLED=false (실험 체결이 섀도 평가에 섞이지 않게) JOB_NAME="CDC Realtime Pipeline [load_test]"
        String topic = System.getenv().getOrDefault("CDC_TOPIC", "cdc.crypto_db.crypto_trades");
        String groupId = System.getenv().getOrDefault("CDC_GROUP_ID", "flink-cdc-consumer");
        String tablePrefix = System.getenv().getOrDefault("CLICKHOUSE_TABLE_PREFIX", "");
        boolean alertsEnabled = !"false".equalsIgnoreCase(System.getenv().getOrDefault("MARKET_ALERTS_ENABLED", "true"));
        String jobName = System.getenv().getOrDefault("JOB_NAME", "CDC Realtime Pipeline");

        // 3. Kafka Source 설정
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(topic)
                .setGroupId(groupId)
                // 2026-09-09: savepoint 없이 재시작해도 커밋된 그룹 오프셋부터 재개 (없으면 latest) — 재시작 유실 방지
                .setStartingOffsets(OffsetsInitializer.committedOffsets(org.apache.kafka.clients.consumer.OffsetResetStrategy.LATEST))
                .setValueOnlyDeserializer(new NullSafeStringSchema())
                .build();

        // 4. Source → CryptoTradeEvent 파싱
        DataStream<CryptoTradeEvent> tradeEvents = env
                .fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "Kafka CDC Source")
                .filter(msg -> msg != null)
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
        aggregated.addSink(ClickHouseSinks.aggregationSink(clickhouseUrl, tablePrefix))
                .name("ClickHouse Aggregation Sink");

        // 6. Stream 2: 이상탐지 v2 — PRICE_24H 등급 전이 → market_alerts (섀도, docs/22)
        // uid 를 명시하는 이유: 구 AnomalyDetector 의 상태(lastPrice 등)를 이어받지 않고 새로 시작한다.
        // 재제출 시 savepoint 의 구 연산자 상태는 --allowNonRestoredState 로 의도적으로 버린다 (근거 없는 규칙의 상태는 보존 가치가 없다).
        if (alertsEnabled) {
            DataStream<MarketAlert> marketAlerts = tradeEvents
                    .filter(event -> "c".equals(event.getOp()))
                    .keyBy(CryptoTradeEvent::getMarket)
                    .process(new MarketAlertDetector())
                    .uid("market-alert-detector-v2")
                    .name("Market Alert Detector (PRICE_24H)");

            marketAlerts.print("ALERT");
            marketAlerts.addSink(ClickHouseSinks.marketAlertSink(clickhouseUrl))
                    .uid("market-alert-sink-v2")
                    .name("ClickHouse Market Alert Sink");
        }

        // 7. Stream 3: Raw 체결 이벤트 → ClickHouse
        tradeEvents.addSink(ClickHouseSinks.rawTradeSink(clickhouseUrl, tablePrefix))
                .name("ClickHouse Raw Trade Sink");

        LOG.info("=== CDC Crypto Pipeline Started ===");
        LOG.info("Kafka: {}", bootstrapServers);
        LOG.info("ClickHouse: {}", clickhouseUrl);
        LOG.info("Topic: {} / group: {} / table prefix: '{}' / alerts: {}", topic, groupId, tablePrefix, alertsEnabled);
        LOG.info("Parallelism: {}", env.getParallelism());
        LOG.info("Window: 5 minutes (tumbling)");

        env.execute(jobName);
    }
}
