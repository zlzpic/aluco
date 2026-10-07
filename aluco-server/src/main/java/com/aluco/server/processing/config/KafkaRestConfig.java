package com.aluco.server.processing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Kafka runtime beans for seam 1 (aluco.sink.type=kafka).
 *
 * - kafkaJsonMapper : single Jackson ObjectMapper so producer (sink) and
 *   consumer (KafkaTelemetryConsumer) serialize/deserialize the TelemetryMessage
 *   record with identical configuration.
 * - dlqProducer     : shared KafkaProducer used by KafkaTelemetryConsumer to
 *   publish to the DLQ topic, so only one producer is constructed, one
 *   lifecycle, one set of retries.
 *
 * The telemetry producer itself is created inside KafkaTelemetrySink because
 * producer config (acks=all, idempotence, snappy) differs from the DLQ
 * producer and Kafka's defaults already cover a sink under load.
 */
@Configuration
@ConditionalOnProperty(name = "aluco.sink.type", havingValue = "kafka")
public class KafkaRestConfig {

    @Bean
    public ObjectMapper kafkaJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean(destroyMethod = "close")
    public KafkaProducer<String, String> dlqProducer(
            @Value("${aluco.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }
}