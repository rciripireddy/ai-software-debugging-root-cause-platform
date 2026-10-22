package com.rootcauseplatform.incident.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.rootcauseplatform.incident.domain.ApplicationInfo;
import com.rootcauseplatform.incident.domain.Severity;

/**
 * Reads stored error events from the ingestion schema. Used only by the deprecated
 * {@code POST /api/v1/incidents} endpoint; it is the last place where incident-service reads
 * another service's tables, and goes away together with that endpoint.
 */
@Component
class IngestionEventReader {

    private final NamedParameterJdbcTemplate jdbc;

    IngestionEventReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<StoredEvent> findByIds(Collection<UUID> ids) {
        return jdbc.query("""
                select e.id, e.event_timestamp, e.severity, e.message, e.exception_type, e.host, e.trace_id,
                       e.correlation_id, e.fingerprint,
                       a.id as application_id, a.name, a.service_name, a.environment
                from ingestion.error_events e
                join ingestion.applications a on a.id = e.application_id
                where e.id in (:ids)""", Map.of("ids", ids), (rs, rowNum) -> new StoredEvent(
                rs.getObject("id", UUID.class),
                new ApplicationInfo(rs.getObject("application_id", UUID.class), rs.getString("name"),
                        rs.getString("service_name"), rs.getString("environment")),
                rs.getTimestamp("event_timestamp").toInstant(),
                Severity.valueOf(rs.getString("severity")),
                rs.getString("message"),
                rs.getString("exception_type"),
                rs.getString("host"),
                rs.getString("trace_id"),
                rs.getString("correlation_id"),
                rs.getString("fingerprint")));
    }

    record StoredEvent(UUID id, ApplicationInfo application, Instant eventTimestamp, Severity severity,
            String message, String exceptionType, String host, String traceId, String correlationId,
            String fingerprint) {
    }
}
