package com.rootcauseplatform.ingestion.monitoring;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import com.rootcauseplatform.ingestion.outbox.OutboxEventRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Size and age of the outbox backlog: events stored but not yet acknowledged by Kafka. The
 * values are read from the database on a schedule, not on every scrape. The age is computed at
 * scrape time from the oldest pending message, so it keeps growing while Kafka is unavailable.
 */
@Component
public class OutboxBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);

    private final OutboxEventRepository outbox;
    private final Clock clock;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicReference<Instant> oldestPending = new AtomicReference<>();

    public OutboxBacklogMetrics(OutboxEventRepository outbox, Clock clock, MeterRegistry registry) {
        this.outbox = outbox;
        this.clock = clock;
        Gauge.builder("rcp.outbox.pending.messages", pending, AtomicLong::get)
                .description("Outbox messages not yet published to Kafka")
                .register(registry);
        Gauge.builder("rcp.outbox.oldest.pending.age", this, OutboxBacklogMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished outbox message (0 when the outbox is empty)")
                .baseUnit("seconds")
                .register(registry);
    }

    /** While the database is unreachable the last known values stay in place. */
    @Scheduled(fixedDelayString = "${app.outbox.metrics-refresh-interval:15s}")
    public void refresh() {
        try {
            pending.set(outbox.countPending());
            oldestPending.set(outbox.oldestPendingCreatedAt());
        } catch (DataAccessException | TransactionException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.atDebug()
                    .setMessage("Outbox backlog metrics not refreshed")
                    .addKeyValue("action", "outbox.metrics_refresh_failed")
                    .addKeyValue("failureReason", cause.getClass().getSimpleName())
                    .log();
        }
    }

    double oldestPendingAgeSeconds() {
        Instant oldest = oldestPending.get();
        return oldest == null ? 0 : Math.max(0, clock.millis() - oldest.toEpochMilli()) / 1000.0;
    }
}
