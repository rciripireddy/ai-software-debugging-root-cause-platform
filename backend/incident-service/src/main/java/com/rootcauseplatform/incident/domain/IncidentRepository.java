package com.rootcauseplatform.incident.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    /**
     * Finds the unresolved incident for an error and locks it until the transaction ends, so
     * concurrent occurrences are counted one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from Incident i
            where i.application.id = :applicationId and i.fingerprint = :fingerprint
              and i.status <> com.rootcauseplatform.incident.domain.IncidentStatus.RESOLVED""")
    Optional<Incident> findActiveForUpdate(UUID applicationId, String fingerprint);
}
