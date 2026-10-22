"""Incidents and events as read from incident-service and ingestion-service (analysis input)."""

from dataclasses import dataclass, replace
from datetime import datetime
from uuid import UUID


@dataclass(frozen=True)
class ApplicationRef:
    id: UUID
    name: str
    service: str
    environment: str


@dataclass(frozen=True)
class EventRecord:
    id: UUID
    timestamp: datetime
    severity: str
    message: str
    exception_type: str | None = None
    host: str | None = None
    trace_id: str | None = None
    correlation_id: str | None = None
    # Only available after the event has been read from ingestion-service.
    stack_trace: str | None = None
    source: str | None = None
    details_loaded: bool = False

    def with_details(self, stack_trace: str | None, source: str | None) -> "EventRecord":
        return replace(self, stack_trace=stack_trace, source=source, details_loaded=True)


@dataclass(frozen=True)
class IncidentRecord:
    id: UUID
    title: str
    status: str
    severity: str
    application: ApplicationRef
    occurrence_count: int
    first_seen_at: datetime
    last_seen_at: datetime
    fingerprint: str
    # The most recent events, newest first (incident-service returns at most 50).
    events: tuple[EventRecord, ...] = ()

    def with_events(self, events: tuple[EventRecord, ...]) -> "IncidentRecord":
        return replace(self, events=events)
