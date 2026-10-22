package com.rootcauseplatform.incident.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;

import com.rootcauseplatform.incident.KafkaTestSupport;
import com.rootcauseplatform.incident.TestData;
import com.rootcauseplatform.incident.TestMessages;
import com.rootcauseplatform.incident.TestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

/** Sends messages to the real topic and checks what the running consumer stored. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ErrorEventConsumerTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    private KafkaTestSupport kafka;
    private UUID applicationId;
    private String applicationName;
    private String fingerprint;

    @BeforeEach
    void setUp() {
        kafka = new KafkaTestSupport(kafkaOperations, kafkaContainer);
        applicationId = UUID.randomUUID();
        applicationName = "consumer-test-" + applicationId.toString().substring(0, 8);
        fingerprint = TestData.randomFingerprint();
    }

    @Test
    void consumesWithTheConfiguredConsumerGroup() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("error-event-consumer");

        assertThat(container).isNotNull();
        assertThat(container.getGroupId()).isEqualTo("incident-service");
    }

    @Test
    void firstOccurrenceOpensAnIncident() {
        UUID eventId = send(fingerprint, "2026-10-12T09:14:05Z", "ERROR");

        Map<String, Object> incident = awaitSingleIncident(fingerprint, 1);
        assertThat(incident)
                .containsEntry("status", "OPEN")
                .containsEntry("severity", "ERROR")
                .containsEntry("occurrence_count", 1)
                .containsEntry("title", "java.net.SocketTimeoutException: Payment authorization failed")
                .containsEntry("application_name", applicationName)
                .containsEntry("service_name", "payment-service")
                .containsEntry("environment", "staging");
        assertThat(instant(incident, "first_seen_at")).isEqualTo("2026-10-12T09:14:05Z");
        assertThat(instant(incident, "last_seen_at")).isEqualTo("2026-10-12T09:14:05Z");
        assertThat(processedOutcome(eventId)).isEqualTo("INCIDENT_CREATED");
        assertThat(eventIdsOf(incident)).containsExactly(eventId.toString());
    }

    @Test
    void repeatedOccurrencesUpdateTheSameIncident() {
        // Out of order on purpose: first/last seen come from the event timestamps, not arrival.
        UUID middle = send(fingerprint, "2026-10-12T11:02:13Z", "ERROR");
        UUID latest = send(fingerprint, "2026-10-12T13:55:01Z", "FATAL");
        UUID earliest = send(fingerprint, "2026-10-12T09:14:05Z", "ERROR");

        Map<String, Object> incident = awaitSingleIncident(fingerprint, 3);
        assertThat(incident).containsEntry("severity", "FATAL").containsEntry("status", "OPEN");
        assertThat(instant(incident, "first_seen_at")).isEqualTo("2026-10-12T09:14:05Z");
        assertThat(instant(incident, "last_seen_at")).isEqualTo("2026-10-12T13:55:01Z");
        assertThat(eventIdsOf(incident))
                .containsExactlyInAnyOrder(earliest.toString(), middle.toString(), latest.toString());
        assertThat(processedOutcome(earliest)).isEqualTo("INCIDENT_UPDATED");
    }

    @Test
    void aDifferentFingerprintOpensASeparateIncident() {
        String otherFingerprint = TestData.randomFingerprint();
        send(fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        send(otherFingerprint, "2026-10-12T09:15:00Z", "ERROR");

        Map<String, Object> first = awaitSingleIncident(fingerprint, 1);
        Map<String, Object> second = awaitSingleIncident(otherFingerprint, 1);

        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
    }

    @Test
    void redeliveredMessagesDoNotCountTwice() {
        UUID eventId = UUID.randomUUID();
        String message = message(eventId, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        for (int delivery = 0; delivery < 3; delivery++) {
            kafka.send(topics.errorEvents(), key(fingerprint), message, null);
        }
        // Same key, so same partition: processed after the duplicates.
        UUID next = send(fingerprint, "2026-10-12T09:20:00Z", "ERROR");

        await().atMost(TIMEOUT).until(() -> processedOutcome(next) != null);
        Map<String, Object> incident = awaitSingleIncident(fingerprint, 2);
        assertThat(eventIdsOf(incident)).containsExactlyInAnyOrder(eventId.toString(), next.toString());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from incident.processed_events where event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void eventsBelowTheMinimumSeverityCreateNoIncident() {
        UUID info = send(fingerprint, "2026-10-12T09:14:02Z", "INFO");
        UUID warning = send(fingerprint, "2026-10-12T09:14:03Z", "WARN");

        await().atMost(TIMEOUT).until(() -> processedOutcome(warning) != null);
        assertThat(processedOutcome(info)).isEqualTo("IGNORED_SEVERITY");
        assertThat(processedOutcome(warning)).isEqualTo("IGNORED_SEVERITY");
        assertThat(incidentsFor(fingerprint)).isEmpty();
    }

    @Test
    void anOccurrenceAfterResolutionOpensANewIncident() {
        send(fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        Map<String, Object> resolved = awaitSingleIncident(fingerprint, 1);
        jdbcTemplate.update("update incident.incidents set status = 'RESOLVED' where id = ?", resolved.get("id"));

        send(fingerprint, "2026-10-13T08:00:00Z", "ERROR");

        await().atMost(TIMEOUT).until(() -> incidentsFor(fingerprint).size() == 2);
        assertThat(incidentsFor(fingerprint))
                .extracting(row -> row.get("status") + "/" + row.get("occurrence_count"))
                .containsExactlyInAnyOrder("RESOLVED/1", "OPEN/1");
    }

    @Test
    void invalidMessagesGoToTheDeadLetterTopicAndProcessingContinues() {
        String malformedKey = "malformed-" + UUID.randomUUID();
        kafka.send(topics.errorEvents(), malformedKey, "{\"eventType\": \"ErrorEventCreated\", ", "corr-malformed");
        Map<String, Object> newerVersion = TestMessages.errorEventCreated(UUID.randomUUID(), applicationId,
                applicationName, fingerprint, "2026-10-12T09:14:05Z", "ERROR");
        newerVersion.put("eventVersion", 2);
        String versionKey = "version-" + UUID.randomUUID();
        kafka.send(topics.errorEvents(), versionKey, jsonMapper.writeValueAsString(newerVersion), null);

        ConsumerRecord<String, String> malformed = kafka.awaitRecord(topics.errorEventsDlt(),
                record -> malformedKey.equals(record.key()));
        ConsumerRecord<String, String> unsupported = kafka.awaitRecord(topics.errorEventsDlt(),
                record -> versionKey.equals(record.key()));

        assertThat(malformed.value()).isEqualTo("{\"eventType\": \"ErrorEventCreated\", ");
        assertThat(KafkaTestSupport.header(malformed, "kafka_dlt-exception-message")).contains("not valid JSON");
        assertThat(KafkaTestSupport.header(malformed, "kafka_dlt-original-topic")).isEqualTo(topics.errorEvents());
        assertThat(KafkaTestSupport.header(malformed, "correlation-id")).isEqualTo("corr-malformed");
        assertThat(KafkaTestSupport.header(unsupported, "kafka_dlt-exception-message"))
                .contains("Unsupported event version: 2");

        send(fingerprint, "2026-10-12T10:00:00Z", "ERROR");
        awaitSingleIncident(fingerprint, 1);
    }

    private UUID send(String eventFingerprint, String timestamp, String severity) {
        UUID eventId = UUID.randomUUID();
        kafka.send(topics.errorEvents(), key(eventFingerprint), message(eventId, eventFingerprint, timestamp, severity),
                "consumer-test");
        return eventId;
    }

    private String message(UUID eventId, String eventFingerprint, String timestamp, String severity) {
        return jsonMapper.writeValueAsString(TestMessages.errorEventCreated(eventId, applicationId, applicationName,
                eventFingerprint, timestamp, severity));
    }

    private String key(String eventFingerprint) {
        return TestMessages.key(applicationId, eventFingerprint);
    }

    private Map<String, Object> awaitSingleIncident(String eventFingerprint, int occurrences) {
        await().atMost(TIMEOUT).until(() -> {
            List<Map<String, Object>> incidents = incidentsFor(eventFingerprint);
            return incidents.size() == 1 && Integer.valueOf(occurrences).equals(incidents.getFirst().get("occurrence_count"));
        });
        return incidentsFor(eventFingerprint).getFirst();
    }

    private List<Map<String, Object>> incidentsFor(String eventFingerprint) {
        return jdbcTemplate.queryForList("""
                select id, title, status, severity, occurrence_count, first_seen_at, last_seen_at,
                       application_name, service_name, environment
                from incident.incidents where application_id = ? and fingerprint = ?""",
                applicationId, eventFingerprint);
    }

    private List<String> eventIdsOf(Map<String, Object> incident) {
        return jdbcTemplate.queryForList(
                "select error_event_id::text from incident.incident_events where incident_id = ?", String.class,
                incident.get("id"));
    }

    private String processedOutcome(UUID eventId) {
        List<String> outcomes = jdbcTemplate.queryForList(
                "select outcome from incident.processed_events where event_id = ?", String.class, eventId);
        return outcomes.isEmpty() ? null : outcomes.getFirst();
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }
}
