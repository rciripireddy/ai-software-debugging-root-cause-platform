"""Temporal evidence: recurrence pattern, host concentration, errors linked by time or trace."""

from collections import Counter
from dataclasses import dataclass
from datetime import timedelta
from statistics import median
from uuid import UUID

from analysis_service.models.analysis import (
    HostDistribution,
    LinkedIncident,
    Recurrence,
    RecurrencePattern,
    Share,
)
from analysis_service.models.records import EventRecord, IncidentRecord

PERSISTENT_SPAN = timedelta(hours=24)
PERSISTENT_MINIMUM_OCCURRENCES = 3


def recurrence(incident: IncidentRecord, window: timedelta) -> Recurrence:
    """Classifies the incident as a single occurrence, a burst, recurring or persistent.

    The span comes from the incident's first/last seen timestamps (all occurrences); window and
    interval statistics come from the analyzed (most recent) events.
    """
    timestamps = sorted(event.timestamp for event in incident.events)
    span = incident.last_seen_at - incident.first_seen_at
    count = incident.occurrence_count

    if count <= 1:
        pattern = RecurrencePattern.SINGLE
    elif span <= window:
        pattern = RecurrencePattern.BURST
    elif span >= PERSISTENT_SPAN and count >= PERSISTENT_MINIMUM_OCCURRENCES:
        pattern = RecurrencePattern.PERSISTENT
    else:
        pattern = RecurrencePattern.RECURRING

    intervals = [(b - a).total_seconds() for a, b in zip(timestamps, timestamps[1:], strict=False)]
    return Recurrence(
        pattern=pattern,
        description=_describe(pattern, count, span, window, _max_in_window(timestamps, window)),
        occurrence_count=count,
        events_analyzed=len(timestamps),
        first_seen_at=incident.first_seen_at,
        last_seen_at=incident.last_seen_at,
        span_seconds=int(span.total_seconds()),
        window_seconds=int(window.total_seconds()),
        max_occurrences_in_window=_max_in_window(timestamps, window),
        median_interval_seconds=int(median(intervals)) if intervals else None,
    )


def _max_in_window(timestamps: list, window: timedelta) -> int:
    best, start = 0, 0
    for end, timestamp in enumerate(timestamps):
        while timestamp - timestamps[start] > window:
            start += 1
        best = max(best, end - start + 1)
    return best


def _describe(
    pattern: RecurrencePattern, count: int, span: timedelta, window: timedelta, in_window: int
) -> str:
    if pattern is RecurrencePattern.SINGLE:
        return "Single occurrence; no recurrence observed."
    if pattern is RecurrencePattern.BURST:
        return f"{count} occurrences within {format_duration(span)} (burst)."
    text = f"{count} occurrences over {format_duration(span)}"
    if in_window > 1:
        text += f"; up to {in_window} within {format_duration(window)}"
    suffix = "persistent" if pattern is RecurrencePattern.PERSISTENT else "recurring"
    return f"{text} ({suffix})."


def format_duration(duration: timedelta) -> str:
    seconds = int(duration.total_seconds())
    if seconds < 60:
        return f"{seconds} second{'s' if seconds != 1 else ''}"
    if seconds < 3600:
        minutes = round(seconds / 60)
        return f"{minutes} minute{'s' if minutes != 1 else ''}"
    if seconds < 172800:
        hours = round(seconds / 3600)
        return f"{hours} hour{'s' if hours != 1 else ''}"
    return f"{round(seconds / 86400)} days"


def host_distribution(events: tuple[EventRecord, ...]) -> HostDistribution:
    hosts = Counter(event.host for event in events if event.host)
    with_host = sum(hosts.values())
    top = sorted(hosts.items(), key=lambda item: (-item[1], item[0]))[:3]
    return HostDistribution(
        distinct=len(hosts),
        top=[Share(value=host, count=count, total=with_host) for host, count in top],
        concentrated=len(hosts) == 1 and with_host >= 2,
    )


@dataclass(frozen=True)
class Link:
    incident: IncidentRecord
    target_event_ids: frozenset[UUID]
    trace_event_ids: frozenset[UUID]
    gaps_seconds: tuple[float, ...]

    def to_model(self) -> LinkedIncident:
        return LinkedIncident(
            incident_id=self.incident.id,
            title=self.incident.title,
            service=self.incident.application.service,
            linked_events=len(self.target_event_ids),
            shared_trace_events=len(self.trace_event_ids),
            median_gap_seconds=round(median(self.gaps_seconds), 3),
            basis="TRACE" if self.trace_event_ids else "TIME",
        )


def _identifiers(event: EventRecord) -> set[str]:
    ids = set()
    if event.trace_id:
        ids.add("trace:" + event.trace_id)
    if event.correlation_id:
        ids.add("correlation:" + event.correlation_id)
    return ids


def linked_incidents(
    target: IncidentRecord,
    pool: list[IncidentRecord],
    window: timedelta,
    *,
    preceding: bool,
) -> list[Link]:
    """Incidents in the same environment with events shortly before (or after) the target's.

    An event pair is linked when the other event happened at most ``window`` before (or after)
    the target event. Pairs sharing a trace or correlation ID are reported as TRACE links;
    incidents linked only by time are reported when they line up with at least two target events
    and half of them, to keep coincidences out.
    """
    links: list[Link] = []
    environment = target.application.environment
    for other in pool:
        if other.id == target.id or other.application.environment != environment:
            continue
        linked: set[UUID] = set()
        traced: set[UUID] = set()
        gaps: list[float] = []
        for event in target.events:
            event_ids = _identifiers(event)
            best_gap: float | None = None
            shares_trace = False
            for candidate in other.events:
                gap = (event.timestamp - candidate.timestamp).total_seconds()
                if not preceding:
                    gap = -gap
                if gap < 0 or gap > window.total_seconds():
                    continue
                if event_ids & _identifiers(candidate):
                    shares_trace = True
                if best_gap is None or gap < best_gap:
                    best_gap = gap
            if best_gap is not None:
                linked.add(event.id)
                gaps.append(best_gap)
                if shares_trace:
                    traced.add(event.id)
        if not linked:
            continue
        if traced or (len(linked) >= 2 and len(linked) * 2 >= len(target.events)):
            links.append(Link(other, frozenset(linked), frozenset(traced), tuple(gaps)))
    return sorted(
        links,
        key=lambda link: (
            -len(link.trace_event_ids),
            -len(link.target_event_ids),
            str(link.incident.id),
        ),
    )
