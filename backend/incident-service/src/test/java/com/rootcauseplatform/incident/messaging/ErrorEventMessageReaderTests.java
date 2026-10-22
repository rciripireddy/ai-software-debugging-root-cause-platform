package com.rootcauseplatform.incident.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rootcauseplatform.incident.TestMessages;
import com.rootcauseplatform.incident.domain.Severity;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

class ErrorEventMessageReaderTests {

    /** The example message published by ingestion-service, shared by both services' tests. */
    private static final Path CONTRACT_EXAMPLE = Path.of("../../docs/events/error-event-created.v1.example.json");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ErrorEventMessageReader reader = new ErrorEventMessageReader(jsonMapper,
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void readsTheDocumentedExampleMessage() throws Exception {
        ErrorEventMessage message = reader.read(Files.readString(CONTRACT_EXAMPLE, StandardCharsets.UTF_8));

        assertThat(message.errorEventId()).isEqualTo(UUID.fromString("01a159ed-2f0a-73d1-94f9-a9b84bbb1a00"));
        assertThat(message.application().name()).isEqualTo("demo-shop");
        assertThat(message.application().service()).isEqualTo("payment-service");
        assertThat(message.eventTimestamp()).isEqualTo(Instant.parse("2026-10-12T09:14:05.402Z"));
        assertThat(message.severity()).isEqualTo(Severity.ERROR);
        assertThat(message.fingerprint()).startsWith("1768a03e1bfb");
        assertThat(message.correlationId()).isNull();
    }

    @Test
    void ignoresUnknownFieldsSoProducersCanAddOptionalFields() {
        Map<String, Object> message = validMessage();
        message.put("addedInALaterRelease", "value");

        assertThat(reader.read(json(message)).errorEventId()).isNotNull();
    }

    @Test
    void rejectsMalformedJson() {
        assertInvalid("{\"eventType\": ", "Message is not valid JSON");
        assertInvalid("[1, 2]", "Message is not a JSON object");
        assertInvalid("", "Message has no content");
    }

    @Test
    void rejectsUnsupportedEventTypesAndVersions() {
        Map<String, Object> otherType = validMessage();
        otherType.put("eventType", "IncidentResolved");
        Map<String, Object> newerVersion = validMessage();
        newerVersion.put("eventVersion", 2);

        assertInvalid(json(otherType), "Unsupported event type");
        assertInvalid(json(newerVersion), "Unsupported event version: 2");
    }

    @Test
    void rejectsMissingAndInvalidFieldsWithoutQuotingTheirValues() {
        Map<String, Object> message = validMessage();
        message.remove("severity");
        message.put("fingerprint", "not-a-fingerprint");
        message.put("traceId", "x".repeat(129));

        assertInvalid(json(message), "Message has missing or invalid fields: fingerprint, severity, traceId");
    }

    @Test
    void rejectsValuesOfTheWrongType() {
        Map<String, Object> message = validMessage();
        message.put("severity", "CRITICAL");

        assertInvalid(json(message), "Message has a field with an invalid value: severity");
    }

    private void assertInvalid(String value, String expectedMessage) {
        assertThatThrownBy(() -> reader.read(value))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage(expectedMessage);
    }

    private static Map<String, Object> validMessage() {
        return TestMessages.errorEventCreated(UUID.randomUUID(), UUID.randomUUID(), "demo-shop",
                "1768a03e1bfbe239bfe6ea3ee3ae7495a804729552f3187a69f7515444348ae7", "2026-10-12T09:14:05Z", "ERROR");
    }

    private String json(Map<String, Object> message) {
        return jsonMapper.writeValueAsString(message);
    }
}
