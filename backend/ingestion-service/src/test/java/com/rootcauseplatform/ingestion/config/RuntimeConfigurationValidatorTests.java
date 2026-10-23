package com.rootcauseplatform.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeConfigurationValidatorTests {

    private static final String SECRET = "a-test-secret-that-is-long-enough-000001";

    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:5432/rootcause")
                .withProperty("spring.kafka.bootstrap-servers", "kafka:29092")
                .withProperty("server.port", "8081")
                .withProperty("app.auth.jwt-secret", SECRET)
                .withProperty("app.auth.service-token", SECRET)
                .withProperty("app.auth.ingestion-api-key", SECRET);
    }

    @Test
    void acceptsValidSettings() {
        assertThat(RuntimeConfigurationValidator.validate(valid())).isEmpty();
        assertThat(RuntimeConfigurationValidator.validate(valid()
                .withProperty("spring.kafka.bootstrap-servers", "kafka-1:9092, kafka-2:9092")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal/rootcause?sslmode=require")))
                .isEmpty();
    }

    @Test
    void namesEveryInvalidSettingWithoutShowingSecretValues() {
        String shortSecret = "too-short-secret";
        MockEnvironment environment = valid()
                .withProperty("spring.datasource.url", "jdbc:postgresql://:5432/rootcause")
                .withProperty("spring.kafka.bootstrap-servers", "kafka")
                .withProperty("server.port", "99999")
                .withProperty("app.auth.jwt-secret", shortSecret);
        environment.setProperty("app.auth.service-token", "");

        List<String> problems = RuntimeConfigurationValidator.validate(environment);

        assertThat(problems).hasSize(5);
        assertThat(String.join("\n", problems))
                .contains("DB_HOST, DB_PORT and DB_NAME", "KAFKA_BOOTSTRAP_SERVERS", "SERVER_PORT",
                        "AUTH_JWT_SECRET", "INTERNAL_SERVICE_TOKEN")
                .doesNotContain(shortSecret);
    }

    @Test
    void rejectsPortsOutsideTheValidRange() {
        assertThat(RuntimeConfigurationValidator.validate(valid()
                .withProperty("spring.datasource.url", "jdbc:postgresql://postgres:70000/rootcause")
                .withProperty("spring.kafka.bootstrap-servers", "kafka:0")))
                .hasSize(2);
    }

    @Test
    void failureIsReportedAsAListOfProblems() {
        var analysis = new InvalidConfigurationFailureAnalyzer().analyze(
                new InvalidConfigurationException(List.of("KAFKA_BOOTSTRAP_SERVERS must be set")));

        assertThat(analysis.getDescription()).contains("ingestion-service", " - KAFKA_BOOTSTRAP_SERVERS must be set");
        assertThat(analysis.getAction()).contains(".env.example");
    }
}
