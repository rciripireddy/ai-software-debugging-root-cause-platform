package com.rootcauseplatform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IncidentServiceApplicationTests {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void healthEndpointReportsUp() {
        ApiClient.Response response = api.get("/actuator/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("UP");
    }

    @Test
    void livenessAndReadinessProbesAreExposed() {
        assertThat(api.get("/actuator/health/liveness").status()).isEqualTo(200);
        assertThat(api.get("/actuator/health/readiness").status()).isEqualTo(200);
    }

    @Test
    void infoEndpointIdentifiesService() {
        ApiClient.Response response = api.get("/actuator/info");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().at("/app/name").asString()).isEqualTo("incident-service");
    }

    @Test
    void flywayCreatesTheIncidentSchema() {
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'incident' order by 1",
                String.class))
                .containsExactly("flyway_schema_history", "incident_events", "incidents", "processed_events");
    }

    @Test
    void incidentTablesHaveNoForeignKeysIntoTheIngestionSchema() {
        Integer crossSchemaForeignKeys = jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where contype = 'f'
                  and connamespace = 'incident'::regnamespace
                  and confrelid::regclass::text like 'ingestion.%'""", Integer.class);

        assertThat(crossSchemaForeignKeys).isZero();
    }

    @Test
    void databaseAllowsOnlyOneUnresolvedIncidentPerErrorAndApplication() {
        UUID applicationId = UUID.randomUUID();
        String fingerprint = TestData.randomFingerprint();
        String insert = """
                insert into incident.incidents (id, application_id, application_name, service_name, environment,
                    title, status, severity, first_seen_at, last_seen_at, occurrence_count, fingerprint)
                values (?, ?, 'schema-test', 'api', 'test', 'Timeout', ?, 'ERROR', now(), now(), 1, ?)""";

        jdbcTemplate.update(insert, UUID.randomUUID(), applicationId, "RESOLVED", fingerprint);
        jdbcTemplate.update(insert, UUID.randomUUID(), applicationId, "OPEN", fingerprint);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), applicationId, "INVESTIGATING",
                fingerprint))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_incidents_active_fingerprint");
    }
}
