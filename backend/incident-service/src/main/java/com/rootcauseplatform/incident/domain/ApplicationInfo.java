package com.rootcauseplatform.incident.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The monitored application an incident belongs to, as reported in the Kafka message.
 * {@code id} identifies the application in ingestion-service; there is no foreign key to it.
 */
@Embeddable
public record ApplicationInfo(
        @Column(name = "application_id", nullable = false, updatable = false) UUID id,
        @Column(name = "application_name", nullable = false, updatable = false) String name,
        @Column(name = "service_name", nullable = false, updatable = false) String serviceName,
        @Column(name = "environment", nullable = false, updatable = false) String environment) {
}
