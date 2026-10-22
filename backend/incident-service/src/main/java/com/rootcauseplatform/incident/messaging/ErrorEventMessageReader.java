package com.rootcauseplatform.incident.messaging;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses and validates {@code ErrorEventCreated} messages. */
@Component
public class ErrorEventMessageReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public ErrorEventMessageReader(JsonMapper jsonMapper, Validator validator) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    /** @throws InvalidEventException if the message cannot be processed */
    public ErrorEventMessage read(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Message has no content");
        }
        JsonNode node;
        try {
            node = jsonMapper.readTree(value);
        } catch (JacksonException e) {
            throw new InvalidEventException("Message is not valid JSON");
        }
        if (!node.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        verifyEnvelope(node);

        ErrorEventMessage message;
        try {
            message = jsonMapper.treeToValue(node, ErrorEventMessage.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Message has a field with an invalid value: " + fieldPath(e));
        }
        Set<ConstraintViolation<ErrorEventMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String fields = violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new InvalidEventException("Message has missing or invalid fields: " + fields);
        }
        return message;
    }

    private static void verifyEnvelope(JsonNode node) {
        JsonNode type = node.get("eventType");
        if (type == null || !type.isString() || !ErrorEventMessage.TYPE.equals(type.stringValue())) {
            throw new InvalidEventException("Unsupported event type");
        }
        JsonNode version = node.get("eventVersion");
        if (version == null || !version.isInt() || version.intValue() != ErrorEventMessage.VERSION) {
            throw new InvalidEventException("Unsupported event version: " + (version == null ? "missing" : version));
        }
    }

    private static String fieldPath(JacksonException e) {
        return e.getPath().stream()
                .map(reference -> reference.getPropertyName() != null
                        ? reference.getPropertyName()
                        : String.valueOf(reference.getIndex()))
                .collect(Collectors.joining("."));
    }
}
