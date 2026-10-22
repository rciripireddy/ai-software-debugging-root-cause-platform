package com.rootcauseplatform.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IngestionServiceApplicationTests {

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
        assertThat(response.json().at("/app/name").asString()).isEqualTo("ingestion-service");
    }

    @Test
    void flywayCreatesTheIngestionSchema() {
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'ingestion' order by 1",
                String.class))
                .containsExactly("applications", "error_events", "flyway_schema_history", "outbox_events");
        assertThat(jdbcTemplate.queryForList(
                "select version from ingestion.flyway_schema_history where success and version is not null",
                String.class))
                .contains("1", "2", "3");
    }
}
