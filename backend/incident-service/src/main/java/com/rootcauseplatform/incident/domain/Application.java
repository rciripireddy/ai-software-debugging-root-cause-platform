package com.rootcauseplatform.incident.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** Read-only view of an application registered by ingestion-service. */
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
}
