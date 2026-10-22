package com.rootcauseplatform.incident.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rootcauseplatform.incident.domain.IncidentStatus;
import com.rootcauseplatform.incident.domain.Severity;

/**
 * Aggregate counts over the incident table for the dashboard overview. Computed with SQL
 * aggregates on every request; no counters are stored.
 */
@Service
public class IncidentStatsService {

    /** Number of services listed in {@link IncidentStats#topServices()}. */
    public static final int TOP_SERVICES = 8;

    private final NamedParameterJdbcTemplate jdbc;

    public IncidentStatsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param days number of UTC calendar days, ending with today, for {@code newIncidentsPerDay}
     */
    @Transactional(readOnly = true)
    public IncidentStats stats(int days, Instant now) {
        Map<IncidentStatus, Long> byStatus = new EnumMap<>(IncidentStatus.class);
        for (IncidentStatus status : IncidentStatus.values()) {
            byStatus.put(status, 0L);
        }
        jdbc.query("SELECT status, count(*) AS n FROM incident.incidents GROUP BY status",
                rs -> {
                    byStatus.put(IncidentStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });

        Map<Severity, Long> unresolvedBySeverity = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            unresolvedBySeverity.put(severity, 0L);
        }
        jdbc.query("""
                SELECT severity, count(*) AS n FROM incident.incidents
                WHERE status <> 'RESOLVED' GROUP BY severity""",
                rs -> {
                    unresolvedBySeverity.put(Severity.valueOf(rs.getString("severity")), rs.getLong("n"));
                });

        Long servicesAffected = jdbc.queryForObject("""
                SELECT count(DISTINCT service_name) FROM incident.incidents WHERE status <> 'RESOLVED'""",
                new MapSqlParameterSource(), Long.class);

        List<ServiceIncidents> topServices = jdbc.query("""
                SELECT service_name, count(*) AS incidents, sum(occurrence_count) AS occurrences
                FROM incident.incidents
                WHERE status <> 'RESOLVED'
                GROUP BY service_name
                ORDER BY incidents DESC, occurrences DESC, service_name
                LIMIT :limit""",
                new MapSqlParameterSource("limit", TOP_SERVICES),
                (rs, row) -> new ServiceIncidents(rs.getString("service_name"), rs.getLong("incidents"),
                        rs.getLong("occurrences")));

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate firstDay = today.minusDays(days - 1L);
        Map<LocalDate, Long> perDay = new HashMap<>();
        jdbc.query("""
                SELECT (first_seen_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n
                FROM incident.incidents
                WHERE first_seen_at >= :since
                GROUP BY day""",
                new MapSqlParameterSource("since", Timestamp.from(firstDay.atStartOfDay(ZoneOffset.UTC).toInstant())),
                rs -> {
                    perDay.put(rs.getObject("day", LocalDate.class), rs.getLong("n"));
                });
        List<DayCount> newIncidentsPerDay = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            newIncidentsPerDay.add(new DayCount(day, perDay.getOrDefault(day, 0L)));
        }

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new IncidentStats(total, byStatus, unresolvedBySeverity,
                servicesAffected == null ? 0 : servicesAffected, topServices, newIncidentsPerDay, now);
    }

    /**
     * @param servicesAffected distinct services with at least one unresolved incident
     * @param newIncidentsPerDay incidents by the UTC day of their first occurrence
     */
    public record IncidentStats(long totalIncidents, Map<IncidentStatus, Long> byStatus,
            Map<Severity, Long> unresolvedBySeverity, long servicesAffected, List<ServiceIncidents> topServices,
            List<DayCount> newIncidentsPerDay, Instant generatedAt) {
    }

    /** Unresolved incidents of a service and their total occurrences. */
    public record ServiceIncidents(String service, long unresolvedIncidents, long occurrences) {
    }

    public record DayCount(LocalDate date, long count) {
    }
}
