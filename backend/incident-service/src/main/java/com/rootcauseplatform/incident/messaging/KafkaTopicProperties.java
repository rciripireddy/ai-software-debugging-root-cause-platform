package com.rootcauseplatform.incident.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka topic settings.
 *
 * @param errorEvents topic with {@code ErrorEventCreated} messages
 * @param errorEventsDlt dead-letter topic for messages that could not be processed
 * @param partitions number of partitions used when the topics are created; the dead-letter
 *        topic has as many as the main topic, so a failed message keeps its partition
 */
@ConfigurationProperties("app.kafka.topics")
public record KafkaTopicProperties(
        @DefaultValue("error-events.v1") String errorEvents,
        @DefaultValue("error-events.v1.dlt") String errorEventsDlt,
        @DefaultValue("3") int partitions) {
}
