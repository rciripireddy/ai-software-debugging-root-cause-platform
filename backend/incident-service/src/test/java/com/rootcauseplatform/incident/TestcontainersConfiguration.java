package com.rootcauseplatform.incident;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the tests against the same PostgreSQL version that docker-compose.yml uses.
 *
 * <p>Incident tables reference tables owned by ingestion-service. Before this service's own
 * migrations run, the ingestion schema is created from ingestion-service's migration files,
 * so tests always use the real schema instead of a copy.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String INGESTION_MIGRATIONS = "filesystem:../ingestion-service/src/main/resources/db/migration";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17-alpine");
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
