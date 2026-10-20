package com.rootcauseplatform.incident.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    @Override
    @EntityGraph(attributePaths = "application")
    Page<Incident> findAll(Specification<Incident> specification, Pageable pageable);

    @EntityGraph(attributePaths = "application")
    Optional<Incident> findWithApplicationById(UUID id);

    @Query("""
            select i.id from Incident i
            where i.application.id = :applicationId and i.fingerprint = :fingerprint
              and i.status <> com.rootcauseplatform.incident.domain.IncidentStatus.RESOLVED""")
    Optional<UUID> findActiveIncidentId(UUID applicationId, String fingerprint);

    /** Returns the IDs of the given error events that already belong to an incident. */
    @Query("select e.id from Incident i join i.events e where e.id in :eventIds")
    List<UUID> findAssignedEventIds(Collection<UUID> eventIds);
}
