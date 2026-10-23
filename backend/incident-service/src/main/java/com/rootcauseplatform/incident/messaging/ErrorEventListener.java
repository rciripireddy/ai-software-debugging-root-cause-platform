package com.rootcauseplatform.incident.messaging;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.rootcauseplatform.incident.logging.CorrelationIdFilter;
import com.rootcauseplatform.incident.monitoring.EventProcessingMetrics;
import com.rootcauseplatform.incident.service.IncidentCorrelationService;
import com.rootcauseplatform.incident.service.IncidentCorrelationService.CorrelationResult;

/**
 * Consumes {@code ErrorEventCreated} messages and correlates them into incidents. Exceptions
 * are handled by the error handler in {@link KafkaConsumerConfiguration} (retry, then
 * dead-letter topic). Offsets are committed after each successfully processed record.
 */
@Component
public class ErrorEventListener {

    private static final Logger log = LoggerFactory.getLogger(ErrorEventListener.class);

    private final ErrorEventMessageReader reader;
    private final IncidentCorrelationService correlationService;
    private final EventProcessingMetrics metrics;

    public ErrorEventListener(ErrorEventMessageReader reader, IncidentCorrelationService correlationService,
            EventProcessingMetrics metrics) {
        this.reader = reader;
        this.correlationService = correlationService;
        this.metrics = metrics;
    }

    /** The consumer group is {@code spring.kafka.consumer.group-id}, not the listener ID. */
    @KafkaListener(id = "error-event-consumer", idIsGroup = false, topics = "${app.kafka.topics.error-events}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String correlationId = header(record, EventHeaders.CORRELATION_ID);
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            ErrorEventMessage message = reader.read(record.value());
            CorrelationResult result = correlationService.correlate(message);
            metrics.consumed(result.outcome());
            log.atInfo()
                    .setMessage("Error event processed")
                    .addKeyValue("action", "event.correlate")
                    .addKeyValue("outcome", result.outcome())
                    .addKeyValue("eventId", message.errorEventId())
                    .addKeyValue("incidentId", result.incidentId())
                    .addKeyValue("applicationId", message.application().id())
                    .addKeyValue("serviceName", message.application().service())
                    .addKeyValue("fingerprint", message.fingerprint())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .log();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
