package com.rootcauseplatform.incident.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.rootcauseplatform.incident.domain.Incident;
import com.rootcauseplatform.incident.domain.IncidentStatus;
import com.rootcauseplatform.incident.domain.Severity;

/** Optional criteria for listing incidents. Null values are ignored. */
public record IncidentFilter(IncidentStatus status, Severity severity, String application, String service,
        String environment) {

    Specification<Incident> toSpecification() {
        List<Specification<Incident>> criteria = new ArrayList<>();
        if (status != null) {
            criteria.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (severity != null) {
            criteria.add((root, query, cb) -> cb.equal(root.get("severity"), severity));
        }
        // Application names are stored in lower case by ingestion-service.
        addApplicationCriterion(criteria, "name", application);
        addApplicationCriterion(criteria, "serviceName", service);
        addApplicationCriterion(criteria, "environment", environment);
        return Specification.allOf(criteria);
    }

    private static void addApplicationCriterion(List<Specification<Incident>> criteria, String attribute,
            String value) {
        if (value != null && !value.isBlank()) {
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            criteria.add((root, query, cb) -> cb.equal(root.get("application").get(attribute), normalized));
        }
    }
}
