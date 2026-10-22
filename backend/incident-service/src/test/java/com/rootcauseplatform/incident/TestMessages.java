package com.rootcauseplatform.incident;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Builds {@code ErrorEventCreated} v1 messages as ingestion-service publishes them. */
public final class TestMessages {

    private TestMessages() {
    }

    public static Map<String, Object> errorEventCreated(UUID eventId, UUID applicationId, String applicationName,
            String fingerprint, String eventTimestamp, String severity) {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("id", applicationId.toString());
        application.put("name", applicationName);
        application.put("service", "payment-service");
        application.put("environment", "staging");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventType", "ErrorEventCreated");
        message.put("eventVersion", 1);
        message.put("errorEventId", eventId.toString());
        message.put("occurredAt", Instant.now().toString());
        message.put("application", application);
        message.put("eventTimestamp", eventTimestamp);
        message.put("severity", severity);
        message.put("exceptionType", "java.net.SocketTimeoutException");
        message.put("message", "Payment authorization failed");
        message.put("fingerprint", fingerprint);
        message.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        message.put("correlationId", null);
        message.put("source", "com.shop.payment.GatewayClient");
        message.put("host", "payment-5d8b7-fghij");
        return message;
    }

    public static String key(UUID applicationId, String fingerprint) {
        return applicationId + ":" + fingerprint;
    }
}
