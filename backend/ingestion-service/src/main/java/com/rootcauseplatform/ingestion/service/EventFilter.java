package com.rootcauseplatform.ingestion.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.rootcauseplatform.ingestion.domain.ErrorEvent;
import com.rootcauseplatform.ingestion.domain.Severity;

/**
 * Optional criteria for listing stored events. Null or blank values are ignored. {@code query}
 * matches the message or exception type, case-insensitively.
 */
public record EventFilter(String application, String service, String environment, Severity severity,
        String query) {

    Specification<ErrorEvent> toSpecification() {
        List<Specification<ErrorEvent>> criteria = new ArrayList<>();
        // Application names are stored in lower case.
        addApplicationCriterion(criteria, "name", application);
        addApplicationCriterion(criteria, "serviceName", service);
        addApplicationCriterion(criteria, "environment", environment);
        if (severity != null) {
            criteria.add((root, q, cb) -> cb.equal(root.get("severity"), severity));
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
            criteria.add((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("message")), pattern, '\\'),
                    cb.like(cb.lower(root.get("exceptionType")), pattern, '\\')));
        }
        return Specification.allOf(criteria);
    }

    private static void addApplicationCriterion(List<Specification<ErrorEvent>> criteria, String attribute,
            String value) {
        if (value != null && !value.isBlank()) {
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            criteria.add((root, q, cb) -> cb.equal(root.get("application").get(attribute), normalized));
        }
    }

    /** Escapes LIKE wildcards so that search text is matched literally. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
