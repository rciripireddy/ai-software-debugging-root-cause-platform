"""Analysis engine: turns an incident, its events and comparable incidents into a report.

Deterministic: the same input (including ``generated_at``) always gives the same report. Event
metadata and raw payloads are never used.
"""

from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta

from analysis_service.analysis.candidates import CandidateContext, build_candidates
from analysis_service.analysis.classification import assess
from analysis_service.analysis.features import EventFeatures, extract_features
from analysis_service.analysis.similarity import IncidentProfile, build_profile, related_incidents
from analysis_service.analysis.temporal import (
    format_duration,
    host_distribution,
    linked_incidents,
    recurrence,
)
from analysis_service.models.analysis import (
    AnalysisReport,
    Candidate,
    Category,
    Confidence,
    DataCoverage,
    IncidentSnapshot,
    Patterns,
    RelatedIncident,
    Share,
)
from analysis_service.models.records import IncidentRecord

# Version of the rules and scoring. Stored with every analysis; increase it when results for the
# same input change.
ANALYSIS_VERSION = "1.0.0"

MAX_RELATED = 10
MAX_LINKED = 5


@dataclass(frozen=True)
class AnalysisSettings:
    burst_window: timedelta = timedelta(minutes=15)
    preceding_window: timedelta = timedelta(minutes=2)
    related_threshold: int = 40


@dataclass(frozen=True)
class _Prepared:
    features: list[EventFeatures]
    profile: IncidentProfile


def _prepare(incident: IncidentRecord) -> _Prepared:
    features = [extract_features(event) for event in incident.events]
    return _Prepared(features, build_profile(incident, features))


def find_related(
    target: IncidentRecord, pool: list[IncidentRecord], settings: AnalysisSettings
) -> list[RelatedIncident]:
    prepared = _prepare(target)
    others = [_prepare(incident).profile for incident in _distinct(target, pool)]
    return related_incidents(
        prepared.profile, others, settings.burst_window, settings.related_threshold
    )[:MAX_RELATED]


def analyze(
    target: IncidentRecord,
    pool: list[IncidentRecord],
    settings: AnalysisSettings,
    generated_at: datetime,
) -> AnalysisReport:
    pool = _distinct(target, pool)
    prepared = _prepare(target)
    features = prepared.features
    profiles = {incident.id: _prepare(incident).profile for incident in pool}

    with_trace = [f for f in features if f.has_stack_trace]
    top_frame = _share(
        [f.top_application_frame.label for f in with_trace if f.top_application_frame],
        len(with_trace),
    )
    patterns = Patterns(
        recurrence=recurrence(target, settings.burst_window),
        dominant_exception=_share([f.exception_type for f in features], len(features)),
        top_application_frame=top_frame,
        repeated_frames=_repeated_frames(with_trace),
        source=_share([f.source for f in features], len(features)),
        hosts=host_distribution(target.events),
        preceding_errors=[
            link.to_model()
            for link in linked_incidents(target, pool, settings.preceding_window, preceding=True)[
                :MAX_LINKED
            ]
        ],
        subsequent_errors=[
            link.to_model()
            for link in linked_incidents(target, pool, settings.preceding_window, preceding=False)[
                :MAX_LINKED
            ]
        ],
    )
    preceding = linked_incidents(target, pool, settings.preceding_window, preceding=True)

    candidates = build_candidates(
        CandidateContext(
            incident=target,
            features=features,
            assessments=assess(features),
            recurrence=patterns.recurrence,
            hosts=patterns.hosts,
            top_frame=top_frame,
            events_with_stack_trace=len(with_trace),
            preceding=preceding,
            profiles=profiles,
        )
    )
    related = related_incidents(
        prepared.profile,
        list(profiles.values()),
        settings.burst_window,
        settings.related_threshold,
    )[:MAX_RELATED]

    coverage = DataCoverage(
        occurrence_count=target.occurrence_count,
        events_analyzed=len(features),
        events_with_details=sum(1 for f in features if f.event.details_loaded),
        events_with_stack_trace=len(with_trace),
        incidents_compared=len(pool),
    )
    primary = candidates[0]
    return AnalysisReport(
        incident_id=target.id,
        analysis_version=ANALYSIS_VERSION,
        generated_at=generated_at,
        incident=IncidentSnapshot(
            id=target.id,
            title=target.title,
            status=target.status,
            severity=target.severity,
            application=target.application.name,
            service=target.application.service,
            environment=target.application.environment,
            occurrence_count=target.occurrence_count,
            first_seen_at=target.first_seen_at,
            last_seen_at=target.last_seen_at,
            fingerprint=target.fingerprint,
        ),
        summary=_summary(target, patterns, primary, related),
        confidence=primary.confidence,
        candidates=candidates,
        patterns=patterns,
        related_incidents=related,
        data_coverage=coverage,
        limitations=_limitations(target, coverage),
    )


def _distinct(target: IncidentRecord, pool: list[IncidentRecord]) -> list[IncidentRecord]:
    """The comparison pool without the target, in a stable order."""
    unique = {incident.id: incident for incident in pool if incident.id != target.id}
    return [unique[key] for key in sorted(unique, key=str)]


def _share(values: list[str | None], total: int) -> Share | None:
    counts = Counter(value for value in values if value)
    if not counts or total == 0:
        return None
    value, count = min(counts.items(), key=lambda item: (-item[1], item[0]))
    return Share(value=value, count=count, total=total)


def _repeated_frames(with_trace: list[EventFeatures]) -> list[Share]:
    """Application frames that occur in at least two events (or the only event)."""
    counts = Counter(frame.label for f in with_trace for frame in f.application_frames)
    minimum = 2 if len(with_trace) > 1 else 1
    frames = [(frame, count) for frame, count in counts.items() if count >= minimum]
    frames.sort(key=lambda item: (-item[1], item[0]))
    return [Share(value=frame, count=count, total=len(with_trace)) for frame, count in frames[:5]]


def _summary(
    incident: IncidentRecord, patterns: Patterns, primary: Candidate, related: list[RelatedIncident]
) -> str:
    application = incident.application
    exception = patterns.dominant_exception.value if patterns.dominant_exception else "Failure"
    text = (
        f"{exception} in {application.service} ({application.environment}): "
        f"{patterns.recurrence.description}"
    )
    if primary.category is Category.UNKNOWN:
        text += " No cause category is supported by the available evidence."
    elif primary.confidence is Confidence.HIGH:
        text += (
            f" Most likely cause based on available evidence (HIGH confidence): {primary.title}."
        )
    else:
        text += f" Potential cause ({primary.confidence.value} confidence): {primary.title}."
    if related:
        strong = sum(1 for r in related if r.strength.value == "STRONG")
        text += f" {len(related)} related incident{'s' if len(related) != 1 else ''}"
        text += f" ({strong} strong)." if strong else "."
    return text


def _limitations(incident: IncidentRecord, coverage: DataCoverage) -> list[str]:
    limitations = [
        "Rule-based analysis of error events only: suggested causes are hypotheses supported by "
        "the listed evidence, not confirmed root causes.",
    ]
    if coverage.events_analyzed < coverage.occurrence_count:
        limitations.append(
            f"Only the {coverage.events_analyzed} most recent of {coverage.occurrence_count} "
            "occurrences were analyzed."
        )
    if coverage.events_with_details < coverage.events_analyzed:
        limitations.append(
            f"Stack traces were read for {coverage.events_with_details} of "
            f"{coverage.events_analyzed} analyzed events."
        )
    if coverage.events_with_stack_trace == 0:
        limitations.append("None of the analyzed events has a stack trace.")
    if coverage.incidents_compared == 0:
        limitations.append("No other incidents of this application were available for comparison.")
    span = incident.last_seen_at - incident.first_seen_at
    if incident.occurrence_count > 1 and span < timedelta(minutes=1):
        limitations.append(
            f"All occurrences fall within {format_duration(span)}; recurrence over time is not yet "
            "established."
        )
    return limitations
