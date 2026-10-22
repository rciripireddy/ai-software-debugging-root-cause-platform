package com.rootcauseplatform.ingestion.domain;

import java.time.Instant;
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

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, UUID>, JpaSpecificationExecutor<ErrorEvent> {

    @Query("select e from ErrorEvent e join fetch e.application where e.id = :id")
    Optional<ErrorEvent> findWithApplicationById(UUID id);

    /** Loads the application with each event, so a page of events needs one query. */
    @Override
    @EntityGraph(attributePaths = "application")
    Page<ErrorEvent> findAll(Specification<ErrorEvent> specification, Pageable pageable);

    /** Events received since the given time, per severity: {@code [Severity, Long]} rows. */
    @Query("select e.severity, count(e) from ErrorEvent e where e.createdAt >= :since group by e.severity")
    List<Object[]> countBySeveritySince(Instant since);

    /**
     * Events received since the given time, per service: {@code [serviceName, total, errors]}
     * rows, where errors counts {@code ERROR} and {@code FATAL} events. Most events first.
     */
    @Query("""
            select a.serviceName, count(e),
                   sum(case when e.severity in (com.rootcauseplatform.ingestion.domain.Severity.ERROR,
                                                com.rootcauseplatform.ingestion.domain.Severity.FATAL)
                            then 1 else 0 end)
            from ErrorEvent e join e.application a
            where e.createdAt >= :since
            group by a.serviceName
            order by count(e) desc, a.serviceName
            """)
    List<Object[]> countByServiceSince(Instant since);
}
