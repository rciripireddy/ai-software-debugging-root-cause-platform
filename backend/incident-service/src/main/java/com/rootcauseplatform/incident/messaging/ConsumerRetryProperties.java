package com.rootcauseplatform.incident.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retry settings for messages whose processing fails, e.g. because the database is briefly
 * unavailable. After the last retry the message goes to the dead-letter topic.
 *
 * @param maxRetries retries after the first failed attempt
 * @param initialInterval delay before the first retry
 * @param multiplier factor applied to the delay after each retry
 * @param maxInterval upper limit for the delay
 */
@ConfigurationProperties("app.kafka.consumer.retry")
public record ConsumerRetryProperties(
        @DefaultValue("3") int maxRetries,
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("10s") Duration maxInterval) {
}
