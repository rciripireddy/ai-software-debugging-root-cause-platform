package com.rootcauseplatform.ingestion.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka topic settings.
 *
 * @param errorEvents topic for {@code ErrorEventCreated} messages
 * @param partitions number of partitions used when the topic is created
 */
@ConfigurationProperties("app.kafka.topics")
public record KafkaTopicProperties(
        @DefaultValue("error-events.v1") String errorEvents,
        @DefaultValue("3") int partitions) {
}
