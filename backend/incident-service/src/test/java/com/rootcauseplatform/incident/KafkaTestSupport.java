package com.rootcauseplatform.incident;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaOperations;
import org.testcontainers.kafka.KafkaContainer;

/** Sends raw messages and reads topics directly, independent of the application's consumer. */
public class KafkaTestSupport {

    private final KafkaOperations<String, String> kafka;
    private final KafkaContainer container;

    public KafkaTestSupport(KafkaOperations<String, String> kafka, KafkaContainer container) {
        this.kafka = kafka;
        this.container = container;
    }

    /** Sends a message and waits until Kafka has acknowledged it. */
    public void send(String topic, String key, String value, String correlationId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        if (correlationId != null) {
            record.headers().add(new RecordHeader("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not send test message", e);
        }
    }

    /** Reads the topic from the beginning until a matching record appears. */
    public ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> matcher) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, container.getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matcher.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record on topic " + topic);
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
