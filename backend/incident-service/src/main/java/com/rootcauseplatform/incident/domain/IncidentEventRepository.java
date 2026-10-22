package com.rootcauseplatform.incident.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEventRepository extends JpaRepository<IncidentEvent, UUID> {

    List<IncidentEvent> findByIncidentIdOrderByEventTimestampDescErrorEventIdDesc(UUID incidentId, Limit limit);
}
