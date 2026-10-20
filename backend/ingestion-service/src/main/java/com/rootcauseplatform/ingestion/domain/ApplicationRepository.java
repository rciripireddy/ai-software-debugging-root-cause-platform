package com.rootcauseplatform.ingestion.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Optional<Application> findByNameAndServiceNameAndEnvironment(String name, String serviceName, String environment);

    /**
     * Registers an application unless it already exists. {@code ON CONFLICT DO NOTHING}
     * makes this safe when several events for a new application arrive at the same time.
     *
     * @return 1 if a row was inserted, 0 if the application already existed
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO ingestion.applications (name, service_name, environment)
            VALUES (:name, :serviceName, :environment)
            ON CONFLICT (name, service_name, environment) DO NOTHING
            """)
    int insertIfAbsent(String name, String serviceName, String environment);
}
