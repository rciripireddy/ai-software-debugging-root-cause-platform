package com.rootcauseplatform.ingestion.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rootcauseplatform.ingestion.logging.CorrelationIdFilter;
import com.rootcauseplatform.ingestion.messaging.ErrorEventCreated;
import com.rootcauseplatform.ingestion.messaging.EventHeaders;

/**
 * Sends pending outbox messages to Kafka. A message is marked published only after Kafka has
 * acknowledged it ({@code acks=all}). A failed message stays pending and is retried later with
 * exponential backoff, so events stored during a Kafka outage are delivered once Kafka is back.
 *
 * <p>Delivery is at least once: if the service stops between the acknowledgement and the database
 * commit, the message is sent again. Consumers must therefore be idempotent.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final KafkaOperations<String, String> kafka;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(OutboxEventRepository outbox, KafkaOperations<String, String> kafka,
            OutboxProperties properties, PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Publishes one batch of due messages in a single transaction that holds row locks on them.
     * Stops at the first failure: when Kafka is down, every further send in the batch would
     * time out as well.
     *
     * @return the number of messages published
     */
    public int publishDueBatch() {
        Integer published = transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = outbox.lockDueBatch(Instant.now(), properties.batchSize());
            int count = 0;
            for (OutboxEvent event : batch) {
                if (!publish(event)) {
                    break;
                }
                count++;
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private boolean publish(OutboxEvent event) {
        String previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (event.getRequestCorrelationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.getRequestCorrelationId());
        }
        try {
            RecordMetadata metadata = send(event).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            event.markPublished(Instant.now());
            log.atInfo()
                    .setMessage("Outbox message published")
                    .addKeyValue("action", "outbox.publish")
                    .addKeyValue("outboxId", event.getId())
                    .addKeyValue("eventId", event.getAggregateId())
                    .addKeyValue("eventType", event.getEventType())
                    .addKeyValue("topic", metadata.topic())
                    .addKeyValue("partition", metadata.partition())
                    .addKeyValue("offset", metadata.offset())
                    .addKeyValue("attempt", event.getAttemptCount())
                    .log();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event, e);
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            recordFailure(event, e);
            return false;
        } finally {
            restoreCorrelationId(previousCorrelationId);
        }
    }

    private CompletableFuture<SendResult<String, String>> send(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getMessageKey(),
                event.getPayload());
        header(record, EventHeaders.EVENT_TYPE, event.getEventType());
        header(record, EventHeaders.EVENT_VERSION, String.valueOf(ErrorEventCreated.VERSION));
        header(record, EventHeaders.CORRELATION_ID, event.getRequestCorrelationId());
        header(record, EventHeaders.PUBLISHED_AT, Instant.now().toString());
        try {
            return kafka.send(record);
        } catch (RuntimeException e) {
            // Some producer errors, such as a metadata timeout, are thrown instead of returned.
            return CompletableFuture.failedFuture(e);
        }
    }

    private void recordFailure(OutboxEvent event, Exception exception) {
        Instant now = Instant.now();
        // The root cause, e.g. a metadata timeout, says more than wrappers like "Send failed".
        // Exception type and message only: stack traces add nothing for a delivery failure.
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        event.markFailed(now, error, now.plus(properties.backoffAfter(event.getAttemptCount() + 1)));

        boolean alert = event.getAttemptCount() % properties.alertEveryAttempts() == 0;
        (alert ? log.atError() : log.atWarn())
                .setMessage(alert ? "Outbox message still unpublished after repeated attempts"
                        : "Outbox message could not be published; will retry")
                .addKeyValue("action", "outbox.publish_failed")
                .addKeyValue("outboxId", event.getId())
                .addKeyValue("eventId", event.getAggregateId())
                .addKeyValue("topic", event.getTopic())
                .addKeyValue("attempt", event.getAttemptCount())
                .addKeyValue("nextAttemptAt", event.getNextAttemptAt())
                .addKeyValue("failureReason", event.getLastError())
                .log();
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static void restoreCorrelationId(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        } else {
            MDC.put(CorrelationIdFilter.MDC_KEY, previous);
        }
    }
}
