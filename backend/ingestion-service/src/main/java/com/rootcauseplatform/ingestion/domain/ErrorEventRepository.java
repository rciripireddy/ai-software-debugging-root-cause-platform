package com.rootcauseplatform.ingestion.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, UUID> {

    @Query("select e from ErrorEvent e join fetch e.application where e.id = :id")
    Optional<ErrorEvent> findWithApplicationById(UUID id);
}
