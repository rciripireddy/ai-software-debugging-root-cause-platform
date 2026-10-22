"""Explainable similarity between two incidents.

Each incident is summarized as a profile (exception chain, application frames, message words,
source, trace IDs, rule category). A pair earns fixed points per matching signal, capped at 100.
A pair is reported as related only when the score reaches the threshold **and** at least
``MINIMUM_SPECIFIC_POINTS`` come from specific signals (same error, exception, code location,
similar message or shared trace). Same service, environment, time or category alone are
context, not evidence of a common cause. Weights are documented in docs/analysis.md.
"""

from collections import Counter
from dataclasses import dataclass
from datetime import timedelta

from analysis_service.analysis.classification import primary_category
from analysis_service.analysis.features import EventFeatures
from analysis_service.analysis.normalization import simple_class_name, token_cosine
from analysis_service.models.analysis import (
    Category,
    MatchingSignal,
    RelatedIncident,
    RelationStrength,
)
from analysis_service.models.records import IncidentRecord

# Points per signal.
SAME_FINGERPRINT = 40
SAME_EXCEPTION_TYPE = 25
SAME_EXCEPTION_NAME = 15  # same simple name, different package
SHARED_CAUSE = 10
SAME_TOP_FRAME = 25
SHARED_FRAMES = 12
SIMILAR_MESSAGE = 20  # multiplied by the cosine similarity
SHARED_TRACE = 30
SAME_SERVICE = 10
SAME_SOURCE = 5
SAME_ENVIRONMENT = 5
TEMPORAL_OVERLAP = 10
SAME_CATEGORY = 10

MESSAGE_SIMILARITY_MINIMUM = 0.5
MINIMUM_SPECIFIC_POINTS = 20
STRONG_SCORE = 75
SPECIFIC_SIGNALS = frozenset(
    {
        "SAME_FINGERPRINT",
        "SAME_EXCEPTION_TYPE",
        "SAME_EXCEPTION_NAME",
        "SHARED_CAUSE",
        "SAME_TOP_FRAME",
        "SHARED_FRAMES",
        "SIMILAR_MESSAGE",
        "SHARED_TRACE",
    }
)


@dataclass(frozen=True)
class IncidentProfile:
    incident: IncidentRecord
    exception_type: str | None
    exception_chain: frozenset[str]
    top_frame_key: str | None
    top_frame_display: str | None
    frame_keys: frozenset[str]
    message_tokens: tuple[str, ...]
    source: str | None
    trace_ids: frozenset[str]
    category: Category


def build_profile(incident: IncidentRecord, features: list[EventFeatures]) -> IncidentProfile:
    """Profile from the analyzed events. Events of one incident share a fingerprint, so one
    event with details is enough for exception, frames and message; IDs come from all events."""
    exception = _most_common([f.exception_type for f in features])
    top_frames = [f.top_application_frame for f in features if f.top_application_frame]
    top_frame = _most_common([frame.key for frame in top_frames])
    representative = next(
        (f for f in features if f.event.details_loaded), features[0] if features else None
    )
    trace_ids = set()
    for event in incident.events:
        if event.trace_id:
            trace_ids.add("trace:" + event.trace_id)
        if event.correlation_id:
            trace_ids.add("correlation:" + event.correlation_id)
    return IncidentProfile(
        incident=incident,
        exception_type=exception,
        exception_chain=frozenset(name for f in features for name in f.exception_chain),
        top_frame_key=top_frame,
        top_frame_display=next((fr.label for fr in top_frames if fr.key == top_frame), None),
        frame_keys=frozenset(frame.key for f in features for frame in f.application_frames),
        message_tokens=tuple(representative.message_tokens) if representative else (),
        source=_most_common([f.source for f in features]),
        trace_ids=frozenset(trace_ids),
        category=primary_category(features) if features else Category.UNKNOWN,
    )


def _most_common(values: list[str | None]) -> str | None:
    counts = Counter(value for value in values if value)
    return min(counts.items(), key=lambda item: (-item[1], item[0]))[0] if counts else None


def score_pair(
    target: IncidentProfile, other: IncidentProfile, window: timedelta
) -> tuple[int, list[MatchingSignal]]:
    signals: list[MatchingSignal] = []

    def add(signal: str, points: int, description: str) -> None:
        signals.append(MatchingSignal(signal=signal, points=points, description=description))

    a, b = target.incident, other.incident
    if a.fingerprint == b.fingerprint:
        if a.application.environment != b.application.environment:
            detail = f"same error in {b.application.environment}"
        elif b.status == "RESOLVED":
            detail = "recurrence of an error from a resolved incident"
        else:
            detail = "same error"
        add("SAME_FINGERPRINT", SAME_FINGERPRINT, f"Same fingerprint ({detail})")

    if target.exception_type and other.exception_type:
        if target.exception_type == other.exception_type:
            add(
                "SAME_EXCEPTION_TYPE",
                SAME_EXCEPTION_TYPE,
                f"Same exception type {target.exception_type}",
            )
        elif simple_class_name(target.exception_type) == simple_class_name(other.exception_type):
            add(
                "SAME_EXCEPTION_NAME",
                SAME_EXCEPTION_NAME,
                f"Same exception name {simple_class_name(target.exception_type)}",
            )
        else:
            shared = sorted(
                (target.exception_chain & other.exception_chain)
                - {target.exception_type, other.exception_type}
            )
            if shared:
                add("SHARED_CAUSE", SHARED_CAUSE, f"Shared exception in cause chain: {shared[0]}")

    if target.top_frame_key and target.top_frame_key == other.top_frame_key:
        add(
            "SAME_TOP_FRAME",
            SAME_TOP_FRAME,
            f"Same top application frame {target.top_frame_display or target.top_frame_key}",
        )
    else:
        shared_frames = sorted(target.frame_keys & other.frame_keys)
        if shared_frames:
            add(
                "SHARED_FRAMES",
                SHARED_FRAMES,
                f"Shared application frame {shared_frames[0]}"
                + (f" and {len(shared_frames) - 1} more" if len(shared_frames) > 1 else ""),
            )

    similarity = token_cosine(list(target.message_tokens), list(other.message_tokens))
    if similarity >= MESSAGE_SIMILARITY_MINIMUM:
        add(
            "SIMILAR_MESSAGE",
            round(SIMILAR_MESSAGE * similarity),
            f"Similar normalized message (cosine similarity {similarity:.2f})",
        )

    shared_ids = target.trace_ids & other.trace_ids
    if shared_ids and a.application.environment == b.application.environment:
        add(
            "SHARED_TRACE",
            SHARED_TRACE,
            f"{len(shared_ids)} shared trace/correlation ID{'s' if len(shared_ids) > 1 else ''}",
        )

    if a.application.service == b.application.service:
        add("SAME_SERVICE", SAME_SERVICE, f"Same service {a.application.service}")
    if target.source and target.source == other.source:
        add("SAME_SOURCE", SAME_SOURCE, f"Same source {target.source}")
    if a.application.environment == b.application.environment:
        add("SAME_ENVIRONMENT", SAME_ENVIRONMENT, f"Same environment {a.application.environment}")
    if a.first_seen_at - window <= b.last_seen_at and b.first_seen_at - window <= a.last_seen_at:
        add("TEMPORAL_OVERLAP", TEMPORAL_OVERLAP, "Active in overlapping time periods")
    if target.category is not Category.UNKNOWN and target.category == other.category:
        add("SAME_CATEGORY", SAME_CATEGORY, f"Same rule category {target.category.value}")

    return min(100, sum(s.points for s in signals)), signals


def related_incidents(
    target: IncidentProfile,
    others: list[IncidentProfile],
    window: timedelta,
    threshold: int,
) -> list[RelatedIncident]:
    """Incidents related to the target, highest score first."""
    related: list[RelatedIncident] = []
    for other in others:
        if other.incident.id == target.incident.id:
            continue
        score, signals = score_pair(target, other, window)
        specific = sum(s.points for s in signals if s.signal in SPECIFIC_SIGNALS)
        if score < threshold or specific < MINIMUM_SPECIFIC_POINTS:
            continue
        incident = other.incident
        related.append(
            RelatedIncident(
                incident_id=incident.id,
                title=incident.title,
                status=incident.status,
                service=incident.application.service,
                environment=incident.application.environment,
                score=score,
                strength=RelationStrength.STRONG
                if score >= STRONG_SCORE
                else RelationStrength.PARTIAL,
                matching_signals=sorted(signals, key=lambda s: (-s.points, s.signal)),
            )
        )
    return sorted(related, key=lambda r: (-r.score, str(r.incident_id)))
