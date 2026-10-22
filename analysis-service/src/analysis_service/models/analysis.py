"""Analysis result. Serialized with camelCase names, both in API responses and in the database."""

from datetime import datetime
from enum import StrEnum
from uuid import UUID

from pydantic import BaseModel, ConfigDict, computed_field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class Category(StrEnum):
    APPLICATION_CODE = "APPLICATION_CODE"
    DOWNSTREAM_DEPENDENCY = "DOWNSTREAM_DEPENDENCY"
    DATABASE = "DATABASE"
    TIMEOUT = "TIMEOUT"
    CONFIGURATION = "CONFIGURATION"
    AUTHENTICATION = "AUTHENTICATION"
    RESOURCE_EXHAUSTION = "RESOURCE_EXHAUSTION"
    SERIALIZATION = "SERIALIZATION"
    UNKNOWN = "UNKNOWN"


class Confidence(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class RecurrencePattern(StrEnum):
    SINGLE = "SINGLE"
    BURST = "BURST"
    RECURRING = "RECURRING"
    PERSISTENT = "PERSISTENT"


class RelationStrength(StrEnum):
    STRONG = "STRONG"
    PARTIAL = "PARTIAL"


class Evidence(ApiModel):
    signal: str
    description: str
    matched: int | None = None
    total: int | None = None


class AffectedComponent(ApiModel):
    service: str
    environment: str
    location: str | None = None
    source: str | None = None


class Candidate(ApiModel):
    rank: int
    category: Category
    title: str
    confidence: Confidence
    score: int
    max_score: int
    confidence_basis: list[str]
    explanation: str
    evidence: list[Evidence]
    affected_component: AffectedComponent
    related_incident_ids: list[UUID] = []
    related_event_ids: list[UUID] = []
    limitations: list[str] = []


class Recurrence(ApiModel):
    pattern: RecurrencePattern
    description: str
    occurrence_count: int
    events_analyzed: int
    first_seen_at: datetime
    last_seen_at: datetime
    span_seconds: int
    window_seconds: int
    max_occurrences_in_window: int
    median_interval_seconds: int | None = None


class Share(ApiModel):
    value: str
    count: int
    total: int


class HostDistribution(ApiModel):
    distinct: int
    top: list[Share]
    concentrated: bool


class LinkedIncident(ApiModel):
    incident_id: UUID
    title: str
    service: str
    linked_events: int
    shared_trace_events: int
    median_gap_seconds: float
    basis: str  # TRACE: same trace/correlation ID; TIME: only close in time


class Patterns(ApiModel):
    recurrence: Recurrence
    dominant_exception: Share | None = None
    top_application_frame: Share | None = None
    repeated_frames: list[Share] = []
    source: Share | None = None
    hosts: HostDistribution
    preceding_errors: list[LinkedIncident] = []
    subsequent_errors: list[LinkedIncident] = []


class MatchingSignal(ApiModel):
    signal: str
    points: int
    description: str


class RelatedIncident(ApiModel):
    incident_id: UUID
    title: str
    status: str
    service: str
    environment: str
    score: int
    strength: RelationStrength
    matching_signals: list[MatchingSignal]


class IncidentSnapshot(ApiModel):
    id: UUID
    title: str
    status: str
    severity: str
    application: str
    service: str
    environment: str
    occurrence_count: int
    first_seen_at: datetime
    last_seen_at: datetime
    fingerprint: str


class DataCoverage(ApiModel):
    occurrence_count: int
    events_analyzed: int
    events_with_details: int
    events_with_stack_trace: int
    incidents_compared: int


class AnalysisReport(ApiModel):
    """Output of the analysis engine: everything except the identifiers assigned on storage."""

    incident_id: UUID
    analysis_version: str
    generated_at: datetime
    incident: IncidentSnapshot
    summary: str
    confidence: Confidence
    candidates: list[Candidate]
    patterns: Patterns
    related_incidents: list[RelatedIncident]
    data_coverage: DataCoverage
    limitations: list[str]

    @computed_field
    @property
    def primary_candidate(self) -> Candidate | None:
        return self.candidates[0] if self.candidates else None


class _StoredIdentity(ApiModel):
    id: UUID
    revision: int


class IncidentAnalysis(AnalysisReport, _StoredIdentity):
    """A stored analysis: every POST adds a new revision; earlier revisions are kept.

    (`_StoredIdentity` is the last base so that `id` and `revision` are serialized first.)
    """


class AnalysisHistoryItem(ApiModel):
    id: UUID
    revision: int
    analysis_version: str
    generated_at: datetime
    confidence: Confidence
    primary_category: Category | None
    primary_title: str | None
    summary: str


class AnalysisHistory(ApiModel):
    incident_id: UUID
    items: list[AnalysisHistoryItem]


class RelatedIncidents(ApiModel):
    incident_id: UUID
    threshold: int
    incidents_compared: int
    items: list[RelatedIncident]
