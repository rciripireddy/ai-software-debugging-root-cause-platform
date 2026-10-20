package com.rootcauseplatform.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * A monitored application. Rows are created through
 * {@link ApplicationRepository#insertIfAbsent}, which is safe under concurrent ingestion,
 * so the entity itself is read-only.
 */
@Entity
@Immutable
@Table(schema = "ingestion", name = "applications")
public class Application {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String environment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Application() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getEnvironment() {
        return environment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
