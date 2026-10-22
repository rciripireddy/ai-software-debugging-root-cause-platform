package com.rootcauseplatform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Upgrades a database that holds Phase 2 incidents to the current schema. */
@Testcontainers
class IncidentMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void decouplingMigrationKeepsExistingIncidentsAndRemovesCrossSchemaForeignKeys() {
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("ingestion")
                .locations(TestcontainersConfiguration.INGESTION_MIGRATIONS).load().migrate();
        incidentMigrations(dataSource).target("1").load().migrate();

        // An incident as Phase 2 stored it: details only in the ingestion schema.
        UUID applicationId = jdbc.queryForObject("""
                insert into ingestion.applications (name, service_name, environment)
                values ('demo-shop', 'payment-service', 'staging') returning id""", UUID.class);
        UUID eventId = UUID.randomUUID();
        String fingerprint = TestData.randomFingerprint();
        jdbc.update("""
                insert into ingestion.error_events (id, application_id, event_timestamp, severity, message,
                    exception_type, host, trace_id, correlation_id, fingerprint, raw_payload)
                values (?, ?, ?, 'ERROR', 'Payment authorization failed', 'java.net.SocketTimeoutException',
                        'payment-5d8b7-fghij', 'trace-1', 'order-1001', ?, '{}'::jsonb)""",
                eventId, applicationId, Timestamp.from(Instant.parse("2026-10-12T09:14:05Z")), fingerprint);
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                insert into incident.incidents (id, application_id, title, status, severity, first_seen_at,
                    last_seen_at, occurrence_count, fingerprint)
                values (?, ?, 'Timeout', 'OPEN', 'ERROR', now(), now(), 1, ?)""", incidentId, applicationId, fingerprint);
        jdbc.update("insert into incident.incident_events (incident_id, error_event_id) values (?, ?)",
                incidentId, eventId);

        incidentMigrations(dataSource).load().migrate();

        assertThat(jdbc.queryForMap(
                "select application_name, service_name, environment from incident.incidents where id = ?", incidentId))
                .containsEntry("application_name", "demo-shop")
                .containsEntry("service_name", "payment-service")
                .containsEntry("environment", "staging");
        Map<String, Object> event = jdbc.queryForMap("""
                select severity, message, exception_type, host, trace_id, correlation_id, event_timestamp
                from incident.incident_events where error_event_id = ?""", eventId);
        assertThat(event)
                .containsEntry("severity", "ERROR")
                .containsEntry("message", "Payment authorization failed")
                .containsEntry("exception_type", "java.net.SocketTimeoutException")
                .containsEntry("host", "payment-5d8b7-fghij")
                .containsEntry("trace_id", "trace-1")
                .containsEntry("correlation_id", "order-1001");
        assertThat(((Timestamp) event.get("event_timestamp")).toInstant()).isEqualTo("2026-10-12T09:14:05Z");
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_constraint
                where contype = 'f' and connamespace = 'incident'::regnamespace
                  and confrelid::regclass::text like 'ingestion.%'""", Integer.class)).isZero();
    }

    private static FluentConfiguration incidentMigrations(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).schemas("incident").locations("classpath:db/migration");
    }
}
