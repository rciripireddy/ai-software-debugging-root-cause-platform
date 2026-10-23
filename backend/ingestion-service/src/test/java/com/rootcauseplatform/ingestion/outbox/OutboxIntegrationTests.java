package com.rootcauseplatform.ingestion.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.kafka.KafkaContainer;

import com.rootcauseplatform.ingestion.ApiClient;
import com.rootcauseplatform.ingestion.TestAuth;
import com.rootcauseplatform.ingestion.TestcontainersConfiguration;
import com.rootcauseplatform.ingestion.domain.Severity;
import com.rootcauseplatform.ingestion.messaging.ErrorEventCreated;
import com.rootcauseplatform.ingestion.messaging.KafkaTopicProperties;
import com.rootcauseplatform.ingestion.monitoring.IngestionMetrics;
import com.rootcauseplatform.ingestion.monitoring.OutboxBacklogMetrics;

import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The background publisher is disabled here, so each test decides when messages are published
 * and with which Kafka client.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "app.outbox.publisher-enabled=false")
@Import(TestcontainersConfiguration.class)
class OutboxIntegrationTests {

    private static final Path CONTRACT_EXAMPLE = Path.of("../../docs/events/error-event-created.v1.example.json");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private KafkaTopicProperties topics;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private IngestionMetrics metrics;

    @Autowired
    private OutboxBacklogMetrics backlogMetrics;

    @Autowired
    private MeterRegistry registry;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = TestAuth.client(port);
        // Start every test with an empty outbox so batches contain only this test's messages.
        jdbcTemplate.update("delete from ingestion.outbox_events");
    }

    @Test
    void storingAnEventAlsoStoresItsOutboxMessage() {
        String eventId = ingest("outbox-app");

        Map<String, Object> row = outboxRow(eventId);
        String applicationId = jdbcTemplate.queryForObject(
                "select application_id::text from ingestion.error_events where id = ?::uuid", String.class, eventId);
        String fingerprint = jdbcTemplate.queryForObject(
                "select fingerprint from ingestion.error_events where id = ?::uuid", String.class, eventId);

        assertThat(row)
                .containsEntry("event_type", "ErrorEventCreated")
                .containsEntry("topic", "error-events.v1")
                .containsEntry("message_key", applicationId + ":" + fingerprint)
                .containsEntry("request_correlation_id", "outbox-test-correlation")
                .containsEntry("attempt_count", 0)
                .containsEntry("published_at", null);
        JsonNode payload = jsonMapper.readTree((String) row.get("payload"));
        assertThat(payload.get("errorEventId").asString()).isEqualTo(eventId);
        assertThat(payload.get("fingerprint").asString()).isEqualTo(fingerprint);
        assertThat(payload.has("stackTrace")).isFalse();
        assertThat(payload.has("metadata")).isFalse();
    }

    @Test
    void rejectedEventsCreateNoOutboxMessage() {
        Map<String, Object> invalid = event("outbox-rejected");
        invalid.remove("severity");

        assertThat(api.postJson("/api/v1/logs", invalid).status()).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ingestion.outbox_events", Integer.class)).isZero();
    }

    @Test
    void messagesMatchTheDocumentedContract() throws Exception {
        JsonNode example = jsonMapper.readTree(Files.readString(CONTRACT_EXAMPLE, StandardCharsets.UTF_8));
        ErrorEventCreated message = new ErrorEventCreated(
                "ErrorEventCreated", 1,
                UUID.fromString("01a159ed-2f0a-73d1-94f9-a9b84bbb1a00"),
                Instant.parse("2026-10-20T15:27:58.731355Z"),
                new ErrorEventCreated.ApplicationRef(UUID.fromString("fb6ee99c-6841-4acb-b651-683d87993cae"),
                        "demo-shop", "payment-service", "staging"),
                Instant.parse("2026-10-12T09:14:05.402Z"), Severity.ERROR, "java.net.SocketTimeoutException",
                "Payment authorization failed", "1768a03e1bfbe239bfe6ea3ee3ae7495a804729552f3187a69f7515444348ae7",
                "4bf92f3577b34da6a3ce929d0e0e4736", null, "com.shop.payment.GatewayClient", "payment-5d8b7-fghij");

        assertThat(jsonMapper.readTree(jsonMapper.writeValueAsString(message))).isEqualTo(example);

        String eventId = ingest("outbox-contract");
        JsonNode stored = jsonMapper.readTree((String) outboxRow(eventId).get("payload"));
        assertThat(fieldNames(stored)).containsExactlyInAnyOrderElementsOf(fieldNames(example));
        assertThat(fieldNames(stored.get("application")))
                .containsExactlyInAnyOrderElementsOf(fieldNames(example.get("application")));
    }

    @Test
    void publishesPendingMessagesOnceKafkaAcknowledgesThem() {
        String eventId = ingest("outbox-publish");
        Map<String, Object> pending = outboxRow(eventId);

        assertThat(publisher.publishDueBatch()).isEqualTo(1);

        Map<String, Object> published = outboxRow(eventId);
        assertThat(published.get("published_at")).isNotNull();
        assertThat(published).containsEntry("attempt_count", 1).containsEntry("last_error", null);

        ConsumerRecord<String, String> record = findPublishedRecord(eventId);
        assertThat(record.key()).isEqualTo(pending.get("message_key"));
        assertThat(header(record, "event-type")).isEqualTo("ErrorEventCreated");
        assertThat(header(record, "event-version")).isEqualTo("1");
        assertThat(header(record, "correlation-id")).isEqualTo("outbox-test-correlation");
        assertThat(header(record, "published-at")).isNotBlank();
        assertThat(jsonMapper.readTree(record.value()).get("errorEventId").asString()).isEqualTo(eventId);
    }

    @Test
    void failedPublishingKeepsTheMessagePendingUntilALaterAttemptSucceeds() {
        String eventId = ingest("outbox-retry");
        Instant beforeFailure = Instant.now();

        assertThat(failingPublisher().publishDueBatch()).isZero();

        Map<String, Object> failed = outboxRow(eventId);
        assertThat(failed).containsEntry("published_at", null).containsEntry("attempt_count", 1);
        assertThat((String) failed.get("last_error")).isEqualTo("TimeoutException: Kafka is unavailable");
        assertThat(((Timestamp) failed.get("next_attempt_at")).toInstant()).isAfter(beforeFailure);
        assertThat(jdbcTemplate.queryForObject("select count(*) from ingestion.error_events where id = ?::uuid",
                Integer.class, eventId)).isEqualTo(1);

        // Not due yet: the backoff delay has not passed.
        assertThat(publisher.publishDueBatch()).isZero();
        assertThat(outboxRow(eventId)).containsEntry("published_at", null);

        // Simulate the backoff delay passing. Clearly in the past, because the database clock and
        // the JVM clock (used by the publisher) can differ by a few milliseconds.
        jdbcTemplate.update("update ingestion.outbox_events set next_attempt_at = now() - interval '1 minute' "
                + "where aggregate_id = ?::uuid", eventId);
        assertThat(publisher.publishDueBatch()).isEqualTo(1);

        Map<String, Object> published = outboxRow(eventId);
        assertThat(published.get("published_at")).isNotNull();
        assertThat(published).containsEntry("attempt_count", 2).containsEntry("last_error", null);
        assertThat(findPublishedRecord(eventId)).isNotNull();
    }

    @Test
    void metricsFollowAcceptedEventsTheBacklogAndPublishingOutcomes() {
        double acceptedBefore = counter("rcp.ingestion.events.accepted", "severity", "error");
        double publishedBefore = counter("rcp.outbox.published");
        double failuresBefore = counter("rcp.outbox.publish.failures");

        String first = ingest("outbox-metrics");
        ingest("outbox-metrics");
        backlogMetrics.refresh();

        assertThat(counter("rcp.ingestion.events.accepted", "severity", "error")).isEqualTo(acceptedBefore + 2);
        assertThat(gauge("rcp.outbox.pending.messages")).isEqualTo(2);
        assertThat(gauge("rcp.outbox.oldest.pending.age")).isGreaterThanOrEqualTo(0);

        failingPublisher().publishDueBatch();
        assertThat(counter("rcp.outbox.publish.failures")).isEqualTo(failuresBefore + 1);

        // The second message is due; the first waits for its backoff delay.
        assertThat(publisher.publishDueBatch()).isEqualTo(1);
        jdbcTemplate.update("update ingestion.outbox_events set next_attempt_at = now() - interval '1 minute' "
                + "where aggregate_id = ?::uuid", first);
        assertThat(publisher.publishDueBatch()).isEqualTo(1);
        backlogMetrics.refresh();

        assertThat(counter("rcp.outbox.published")).isEqualTo(publishedBefore + 2);
        assertThat(gauge("rcp.outbox.pending.messages")).isZero();
        assertThat(gauge("rcp.outbox.oldest.pending.age")).isZero();
    }

    @Test
    void prometheusEndpointExportsTheMetricsWithoutIdentifiersInLabels() {
        String eventId = ingest("outbox-scrape");

        // Anonymous, like Prometheus.
        ApiClient.Response response = new ApiClient(port).get("/actuator/prometheus");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("rcp_ingestion_events_accepted_total{severity=\"error\"}")
                .contains("rcp_outbox_pending_messages")
                .contains("rcp_outbox_oldest_pending_age_seconds")
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_active")
                .doesNotContain(eventId);
    }

    @Test
    void stopsTheBatchAtTheFirstFailure() {
        List<String> eventIds = List.of(ingest("outbox-batch"), ingest("outbox-batch"), ingest("outbox-batch"));

        failingPublisher().publishDueBatch();

        List<Integer> attempts = eventIds.stream().map(id -> (Integer) outboxRow(id).get("attempt_count")).toList();
        assertThat(attempts).containsExactly(1, 0, 0);
    }

    @Test
    void backoffDoublesUpToTheConfiguredMaximum() {
        assertThat(outboxProperties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(outboxProperties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(outboxProperties.backoffAfter(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(outboxProperties.backoffAfter(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(outboxProperties.backoffAfter(1000)).isEqualTo(Duration.ofSeconds(60));
    }

    @SuppressWarnings("unchecked")
    private OutboxPublisher failingPublisher() {
        KafkaOperations<String, String> unavailableKafka = mock(KafkaOperations.class);
        when(unavailableKafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Kafka is unavailable")));
        return new OutboxPublisher(outboxRepository, unavailableKafka, outboxProperties, transactionManager, metrics);
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private String ingest(String application) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs"))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", "outbox-test-correlation")
                .header("X-Api-Key", TestAuth.API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(event(application))))
                .build();
        ApiClient.Response response = api.send(request);
        assertThat(response.status()).isEqualTo(201);
        return response.json().get("id").asString();
    }

    private static Map<String, Object> event(String application) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("application", application);
        event.put("service", "payment-service");
        event.put("environment", "test");
        event.put("timestamp", "2026-10-12T09:14:05.402Z");
        event.put("severity", "ERROR");
        event.put("message", "Payment authorization failed");
        event.put("exceptionType", "java.net.SocketTimeoutException");
        event.put("stackTrace", "java.net.SocketTimeoutException: Read timed out\n\tat com.shop.Gateway.call(Gateway.java:1)");
        event.put("metadata", Map.of("dataset", "test"));
        return event;
    }

    private Map<String, Object> outboxRow(String eventId) {
        return jdbcTemplate.queryForMap("""
                select event_type, topic, message_key, payload::text as payload, request_correlation_id,
                       attempt_count, published_at, next_attempt_at, last_error
                from ingestion.outbox_events where aggregate_id = ?::uuid""", eventId);
    }

    private ConsumerRecord<String, String> findPublishedRecord(String eventId) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = new ArrayList<>();
            consumer.partitionsFor(topics.errorEvents())
                    .forEach(info -> partitions.add(new TopicPartition(info.topic(), info.partition())));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(eventId)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Event " + eventId + " was not found on topic " + topics.errorEvents());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static List<String> fieldNames(JsonNode node) {
        return List.copyOf(node.propertyNames());
    }
}
