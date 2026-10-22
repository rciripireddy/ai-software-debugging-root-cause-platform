package com.rootcauseplatform.ingestion.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox publisher settings.
 *
 * @param publisherEnabled whether the background publisher runs (disabled in some tests)
 * @param pollInterval pause between publishing rounds
 * @param batchSize maximum number of messages sent per round
 * @param sendTimeout how long to wait for Kafka to acknowledge one message
 * @param initialBackoff delay before retrying a failed message; doubles per failed attempt
 * @param maxBackoff upper limit for the retry delay
 * @param alertEveryAttempts log at ERROR level every this many failed attempts of one message
 * @param retention how long published messages are kept before they are deleted
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean publisherEnabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("60s") Duration maxBackoff,
        @DefaultValue("10") int alertEveryAttempts,
        @DefaultValue("7d") Duration retention) {

    /** Delay before the next attempt after {@code attempts} failed attempts (1, 2, 4, ... seconds, capped). */
    Duration backoffAfter(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 30);
        Duration delay = initialBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
