package com.aluco.server.processing;

import com.aluco.server.common.TelemetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Properties;

@Component
@ConditionalOnProperty(name = "aluco.sink.type", havingValue = "kafka")
public class KafkaTelemetrySink implements TelemetrySink {

    private static final Logger log = LoggerFactory.getLogger(KafkaTelemetrySink.class);
    private static final String TOPIC = "aluco.telemetry";
    private static final int MAX_RETRIES = 3;

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;

    public KafkaTelemetrySink(
            @Value("${aluco.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            ObjectMapper kafkaJsonMapper) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, MAX_RETRIES);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        this.producer = new KafkaProducer<>(props);
        this.objectMapper = kafkaJsonMapper;
    }

    @Override
    public void emit(TelemetryMessage msg) {
        try {
            String key = msg.deviceKey();
            String value = objectMapper.writeValueAsString(msg);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC, null, msg.ts(), key, value);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to send telemetry to Kafka: {}", exception.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Kafka emit error: {}", e.getMessage());
        }
    }

    @PostConstruct
    public void init() {
        log.info("KafkaTelemetrySink initialized: topic={}", TOPIC);
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}
