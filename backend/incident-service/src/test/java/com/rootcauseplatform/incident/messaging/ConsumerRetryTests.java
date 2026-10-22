package com.rootcauseplatform.incident.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;

import com.rootcauseplatform.incident.KafkaTestSupport;
import com.rootcauseplatform.incident.TestData;
import com.rootcauseplatform.incident.TestMessages;
import com.rootcauseplatform.incident.TestcontainersConfiguration;
import com.rootcauseplatform.incident.service.IncidentCorrelationService;

import tools.jackson.databind.json.JsonMapper;

/** Failure handling with short retry intervals: retry, then dead-letter topic. */
@SpringBootTest(properties = {
        "app.kafka.consumer.retry.max-retries=3",
        "app.kafka.consumer.retry.initial-interval=100ms",
        "app.kafka.consumer.retry.max-interval=200ms"
})
@Import(TestcontainersConfiguration.class)
class ConsumerRetryTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @MockitoSpyBean
    private IncidentCorrelationService correlationService;

    @MockitoSpyBean
    private ErrorEventMessageReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaTopicProperties topics;

    @Autowired
    private KafkaOperations<String, String> kafkaOperations;

    @Autowired
    private KafkaContainer kafkaContainer;

    private KafkaTestSupport kafka;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        kafka = new KafkaTestSupport(kafkaOperations, kafkaContainer);
        applicationId = UUID.randomUUID();
    }

    @Test
    void aTransientFailureIsRetriedAndTheEventIsThenProcessed() {
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("Database briefly unavailable"))
                .doCallRealMethod()
                .when(correlationService).correlate(argThat(message -> message.errorEventId().equals(eventId)));

        String fingerprint = send(eventId);

        await().atMost(TIMEOUT).until(() -> incidentCount(fingerprint) == 1);
        verify(correlationService, times(2)).correlate(argThat(message -> message.errorEventId().equals(eventId)));
    }

    @Test
    void aPersistentFailureIsRetriedABoundedNumberOfTimesThenDeadLettered() {
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("Database unavailable"))
                .when(correlationService).correlate(argThat(message -> message.errorEventId().equals(eventId)));

        String fingerprint = send(eventId);

        ConsumerRecord<String, String> deadLetter = kafka.awaitRecord(topics.errorEventsDlt(),
                record -> record.value().contains(eventId.toString()));
        // One delivery plus three retries.
        verify(correlationService, times(4)).correlate(argThat(message -> message.errorEventId().equals(eventId)));
        assertThat(KafkaTestSupport.header(deadLetter, "kafka_dlt-exception-message")).contains("Database unavailable");
        assertThat(incidentCount(fingerprint)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from incident.processed_events where event_id = ?", Integer.class, eventId))
                .isZero();

        // The consumer moved on: later messages are processed normally.
        String nextFingerprint = send(UUID.randomUUID());
        await().atMost(TIMEOUT).until(() -> incidentCount(nextFingerprint) == 1);
    }

    @Test
    void anInvalidMessageIsNotRetried() {
        String malformed = "not-json-" + UUID.randomUUID();

        kafka.send(topics.errorEvents(), "invalid", malformed, null);

        kafka.awaitRecord(topics.errorEventsDlt(), record -> malformed.equals(record.value()));
        verify(reader, times(1)).read(eq(malformed));
    }

    private String send(UUID eventId) {
        String fingerprint = TestData.randomFingerprint();
        String message = jsonMapper.writeValueAsString(TestMessages.errorEventCreated(eventId, applicationId,
                "retry-test", fingerprint, "2026-10-12T09:14:05Z", "ERROR"));
        kafka.send(topics.errorEvents(), TestMessages.key(applicationId, fingerprint), message, "retry-test");
        return fingerprint;
    }

    private int incidentCount(String fingerprint) {
        return jdbcTemplate.queryForObject(
                "select count(*) from incident.incidents where application_id = ? and fingerprint = ?",
                Integer.class, applicationId, fingerprint);
    }
}
