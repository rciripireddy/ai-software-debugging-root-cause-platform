package com.rootcauseplatform.incident;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the tests against the same PostgreSQL and Kafka versions that docker-compose.yml uses.
 *
 * <p>The first incident migration (V1, from Phase 2) references ingestion tables, so the
 * ingestion schema is created first from ingestion-service's own migration files. The
 * deprecated manual incident endpoint also reads those tables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String INGESTION_MIGRATIONS = "filesystem:../ingestion-service/src/main/resources/db/migration";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:4.3.1");
    }

    @Bean
    FlywayMigrationStrategy migrateIngestionSchemaFirst() {
        return flyway -> {
            Flyway.configure()
                    .dataSource(flyway.getConfiguration().getDataSource())
                    .schemas("ingestion")
                    .locations(INGESTION_MIGRATIONS)
                    .load()
                    .migrate();
            flyway.migrate();
        };
    }
}
