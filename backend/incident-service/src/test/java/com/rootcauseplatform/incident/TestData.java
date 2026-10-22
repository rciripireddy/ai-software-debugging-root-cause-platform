package com.rootcauseplatform.incident;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts applications and error events into the ingestion schema, as ingestion-service would.
 * Needed only by tests of the deprecated manual incident endpoint, which reads those tables.
 */
public class TestData {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;

    public TestData(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID application(String name, String service, String environment) {
        return jdbcTemplate.queryForObject("""
                insert into ingestion.applications (name, service_name, environment)
                values (?, ?, ?) returning id""", UUID.class, name, service, environment);
    }

    public UUID errorEvent(UUID applicationId, String fingerprint, String timestamp, String severity) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into ingestion.error_events
                    (id, application_id, event_timestamp, severity, message, exception_type, host, trace_id,
                     fingerprint, raw_payload)
                values (?, ?, ?, ?, 'Payment authorization failed', 'java.net.SocketTimeoutException',
                        'payment-5d8b7-fghij', '4bf92f3577b34da6a3ce929d0e0e4736', ?, '{}'::jsonb)""",
                id, applicationId, Timestamp.from(Instant.parse(timestamp)), severity, fingerprint);
        return id;
    }

    public void setStatus(String incidentId, String status) {
        jdbcTemplate.update("update incident.incidents set status = ? where id = ?::uuid", status, incidentId);
    }

    public static String randomFingerprint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
