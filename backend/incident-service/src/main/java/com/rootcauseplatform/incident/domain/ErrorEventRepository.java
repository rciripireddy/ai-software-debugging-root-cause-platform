package com.rootcauseplatform.incident.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Read-only access to error events stored by ingestion-service. */
public interface ErrorEventRepository extends Repository<ErrorEvent, UUID> {

    @EntityGraph(attributePaths = "application")
    List<ErrorEvent> findAllByIdIn(Collection<UUID> ids);

    @Query("""
            select e from Incident i join i.events e
            where i.id = :incidentId
            order by e.eventTimestamp desc, e.id desc""")
    List<ErrorEvent> findLatestByIncidentId(UUID incidentId, Limit limit);
}
