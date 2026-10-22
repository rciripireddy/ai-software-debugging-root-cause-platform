package com.rootcauseplatform.ingestion.outbox;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

/** Runs the outbox publisher periodically and removes old published messages. */
@Component
@ConditionalOnProperty(name = "app.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisher publisher;
    private final OutboxEventRepository outbox;
    private final OutboxProperties properties;

    public OutboxScheduler(OutboxPublisher publisher, OutboxEventRepository outbox, OutboxProperties properties) {
        this.publisher = publisher;
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * Publishes due messages, continuing while full batches succeed so a backlog drains quickly.
     * While the database is unreachable each round fails; that is logged once per round as a
     * warning instead of the scheduler's default error log with a full stack trace.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1s}")
    public void publishPendingMessages() {
        try {
            int published;
            do {
                published = publisher.publishDueBatch();
            } while (published == properties.batchSize());
        } catch (TransactionException | DataAccessException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.atWarn()
                    .setMessage("Outbox publisher cannot reach the database; will retry")
                    .addKeyValue("action", "outbox.poll_failed")
                    .addKeyValue("failureReason", cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    .log();
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    public void deleteOldPublishedMessages() {
        int deleted = outbox.deletePublishedBefore(Instant.now().minus(properties.retention()));
        if (deleted > 0) {
            log.atInfo()
                    .setMessage("Deleted old published outbox messages")
                    .addKeyValue("action", "outbox.cleanup")
                    .addKeyValue("deleted", deleted)
                    .log();
        }
    }
}
