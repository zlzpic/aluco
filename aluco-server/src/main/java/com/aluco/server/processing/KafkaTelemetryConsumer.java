package com.aluco.server.processing;

import com.aluco.server.common.TelemetryMessage;
import com.aluco.server.common.TelemetryPoint;
import com.aluco.server.device.StateStore;
import com.aluco.server.push.LivePush;
import com.aluco.server.alerting.AlertingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced Kafka consumer with retry, DLQ, and monitoring (v2 spec 6.1).
 * Activated when aluco.sink.type=kafka.
 *
 * Features:
 * - Retry with exponential backoff (max 3 attempts)
 * - DLQ for persistently failing messages
 * - Micrometer metrics: aluco.kafka.lag, aluco.kafka.dlq
 */
@Component
@ConditionalOnProperty(name = "aluco.sink.type", havingValue = "kafka")
public class KafkaTelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTelemetryConsumer.class);
    private static final String TOPIC = "aluco.telemetry";
    private static final String DLQ_TOPIC = "aluco.telemetry.dlq";
    private static final String GROUP_ID = "aluco-server";
    private static final int MAX_RETRIES = 3;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 500;

    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> dlqProducer;
    private final TimeSeriesStore timeSeriesStore;
    private final StateStore stateStore;
    private final LivePush livePush;
    private final AlertingEngine alertingEngine;
    private final PresenceTracker presenceTracker;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Counter dlqCounter;
    private final AtomicLong kafkaLag = new AtomicLong(0);
    private volatile boolean running = true;
    private Thread consumerThread;

    public KafkaTelemetryConsumer(
            @Value("${aluco.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            TimeSeriesStore timeSeriesStore,
            StateStore stateStore,
            LivePush livePush,
            AlertingEngine alertingEngine,
            PresenceTracker presenceTracker,
            MeterRegistry meterRegistry,
            KafkaProducer<String, String> dlqProducer,
            ObjectMapper objectMapper) {
        this.timeSeriesStore = timeSeriesStore;
        this.stateStore = stateStore;
        this.livePush = livePush;
        this.alertingEngine = alertingEngine;
        this.presenceTracker = presenceTracker;
        this.meterRegistry = meterRegistry;
        this.dlqCounter = meterRegistry.counter("aluco.kafka.dlq");
        this.dlqProducer = dlqProducer;
        this.objectMapper = objectMapper;

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, BATCH_SIZE);
        this.consumer = new KafkaConsumer<>(consumerProps);
        meterRegistry.gauge("aluco.kafka.lag", kafkaLag);
    }

    @PostConstruct
    public void start() {
        consumerThread = new Thread(this::consumeLoop, "aluco-kafka-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("KafkaTelemetryConsumer started: topic={}, group={}", TOPIC, GROUP_ID);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        if (consumer != null) {
            consumer.close();
        }
    }

    private void consumeLoop() {
        consumer.subscribe(Collections.singletonList(TOPIC));

        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    continue;
                }

                // Process batch with retry logic
                processBatch(records);

                // Gauge: unconsumed records ahead of the current position (spec 6.1)
                updateLag();

                // Commit offset after successful processing
                consumer.commitSync();
            } catch (Exception e) {
                log.error("Kafka consumer error: {}", e.getMessage(), e);
            }
        }
    }

    /** Max consumer lag across assigned partitions: log-end-offset minus current position. */
    private void updateLag() {
        try {
            Set<TopicPartition> parts = consumer.assignment();
            if (parts.isEmpty()) {
                return;
            }
            Map<TopicPartition, Long> ends = consumer.endOffsets(parts);
            long total = 0;
            for (TopicPartition tp : parts) {
                long pos = consumer.position(tp);
                total += Math.max(0, ends.getOrDefault(tp, pos) - pos);
            }
            kafkaLag.set(total);
        } catch (Exception e) {
            log.debug("kafka lag update skipped: {}", e.getMessage());
        }
    }

    private void processBatch(ConsumerRecords<String, String> records) {
        Map<String, Integer> retryCount = new HashMap<>();

        for (var record : records) {
            String key = record.key();
            int attempts = retryCount.getOrDefault(key, 0) + 1;

            try {
                processMessage(record.value());
                retryCount.remove(key); // Success
            } catch (Exception e) {
                log.warn("Kafka message processing failed (attempt {}/{}): {}",
                        attempts, MAX_RETRIES, e.getMessage());

                if (attempts >= MAX_RETRIES) {
                    // Send to DLQ
                    sendToDlq(record.value(), "max_retries_exceeded: " + e.getMessage());
                    dlqCounter.increment();
                    retryCount.remove(key);
                } else {
                    retryCount.put(key, attempts);
                    // Exponential backoff before retry
                    try {
                        Thread.sleep((long) Math.pow(2, attempts) * 100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void sendToDlq(String payload, String reason) {
        try {
            var record = new ProducerRecord<String, String>(
                    DLQ_TOPIC, null, System.currentTimeMillis(), null, payload);
            record.headers().add(new RecordHeader(
                    "error_reason", reason.getBytes()));
            dlqProducer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to send message to DLQ: {}", exception.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to send message to DLQ: {}", e.getMessage());
        }
    }

    protected void processMessage(String payload) {
        try {
            TelemetryMessage msg = objectMapper.readValue(payload, TelemetryMessage.class);

            // Cold path: write to TimeSeriesStore
            List<TelemetryPoint> points = msg.metrics().entrySet().stream()
                    .map(e -> new TelemetryPoint(msg.deviceKey(), e.getKey(), msg.ts(), e.getValue()))
                    .toList();
            timeSeriesStore.writeBatch(points);

            // Hot path: update state + push
            var merged = stateStore.get(msg.deviceKey())
                    .map(s -> new java.util.HashMap<>(s.metrics()))
                    .orElseGet(java.util.HashMap::new);
            merged.putAll(msg.metrics());
            stateStore.upsert(new com.aluco.server.common.DeviceState(
                    msg.deviceKey(), merged, true, msg.ts()));
            livePush.pushTelemetry(msg.deviceKey(), msg.ts(), msg.metrics());

            // presence: track online transition
            presenceTracker.recordReport(msg.deviceKey());

            // Alert path
            alertingEngine.onTelemetry(msg);

        } catch (Exception e) {
            log.error("Failed to process Kafka message: {}", e.getMessage());
            throw new RuntimeException("Kafka message processing failed", e);
        }
    }
}
