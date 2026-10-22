package com.rootcauseplatform.ingestion.outbox;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.logging.CorrelationIdFilter;
import com.rootcauseplatform.ingestion.messaging.ErrorEventCreated;
import com.rootcauseplatform.ingestion.messaging.KafkaTopicProperties;

import tools.jackson.databind.json.JsonMapper;

/** Records an {@code ErrorEventCreated} message for a newly stored error event. */
@Component
public class ErrorEventOutbox {

    private final OutboxEventRepository outbox;
    private final KafkaTopicProperties topics;
    private final JsonMapper jsonMapper;

    public ErrorEventOutbox(OutboxEventRepository outbox, KafkaTopicProperties topics, JsonMapper jsonMapper) {
        this.outbox = outbox;
        this.topics = topics;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Must run in the transaction that stores the event: both rows are committed together or
     * not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(ErrorEvent event) {
        ErrorEventCreated message = ErrorEventCreated.from(event);
        return outbox.save(new OutboxEvent(
                event.getId(),
                ErrorEventCreated.TYPE,
                topics.errorEvents(),
                message.messageKey(),
                jsonMapper.writeValueAsString(message),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                event.getCreatedAt()));
    }
}
