"""Root-cause candidates: evidence, score, confidence, explanation and limitations.

Candidates come from two sources:

1. category rules (classification.py) that the incident's events match, and
2. trace propagation: errors in another incident that precede this incident's events within the
   same trace or correlation ID. This becomes (or strengthens) a DOWNSTREAM_DEPENDENCY candidate.

When neither yields a candidate, a single UNKNOWN candidate states that the evidence is
insufficient. Candidate titles never claim a confirmed cause.
"""

from dataclasses import dataclass, field
from uuid import UUID

from analysis_service.analysis import confidence
from analysis_service.analysis.classification import (
    CATEGORY_ORDER,
    KIND_POINTS,
    CategoryAssessment,
    SignalKind,
)
from analysis_service.analysis.features import EventFeatures
from analysis_service.analysis.normalization import simple_class_name
from analysis_service.analysis.similarity import IncidentProfile
from analysis_service.analysis.temporal import Link
from analysis_service.models.analysis import (
    AffectedComponent,
    Candidate,
    Category,
    Confidence,
    Evidence,
    HostDistribution,
    Recurrence,
    Share,
)
from analysis_service.models.records import IncidentRecord

MAX_CANDIDATES = 5
MAX_EVENT_IDS = 20

_KIND_PHRASES = {
    SignalKind.EXCEPTION_TYPE: "exception type matches the {label} rules",
    SignalKind.MESSAGE: "messages match the {label} rules",
    SignalKind.COMPONENT: "stack frames or source match the {label} rules",
}

_EXPLANATIONS = {
    Category.DATABASE: (
        "Database exception types, database driver or connection-pool frames, or database error "
        "messages show that the failure occurred while accessing the database."
    ),
    Category.AUTHENTICATION: (
        "The exception types or messages show that credentials, tokens or permissions were "
        "rejected."
    ),
    Category.SERIALIZATION: (
        "The exception types or messages show that data could not be parsed or converted, which "
        "points to a malformed or incompatible payload."
    ),
    Category.CONFIGURATION: (
        "The exception types or messages point to missing or invalid configuration, such as an "
        "unresolved property or a component that could not be created."
    ),
    Category.RESOURCE_EXHAUSTION: (
        "The exception types or messages show that a resource limit was reached (memory, "
        "threads, file handles, disk space or a bounded queue)."
    ),
    Category.TIMEOUT: (
        "The exception types or messages show that an operation exceeded its time limit."
    ),
    Category.DOWNSTREAM_DEPENDENCY: (
        "The errors show a failed call to another service or remote endpoint (connection errors, "
        "5xx responses or network-client frames)."
    ),
    Category.APPLICATION_CODE: (
        "The exception is a programming-error type such as a null reference, an invalid argument "
        "or an invalid state. This points to a defect or an unhandled input in the code path "
        "rather than to an infrastructure problem."
    ),
    Category.UNKNOWN: (
        "None of the category rules match the exception types, messages or stack frames of this "
        "incident strongly enough to suggest a cause."
    ),
}

_LIMITATIONS = {
    Category.DATABASE: (
        "No database server metrics (connections, locks, load) are available; the analysis "
        "relies on client-side errors only."
    ),
    Category.AUTHENTICATION: (
        "The state of the identity provider and of the credentials is not visible, so it cannot "
        "be determined whether tokens were expired, revoked or misconfigured."
    ),
    Category.SERIALIZATION: (
        "Request payloads are not analyzed, so the offending field or value cannot be identified."
    ),
    Category.CONFIGURATION: "Deployed configuration values are not available for inspection.",
    Category.RESOURCE_EXHAUSTION: (
        "No host or container metrics (memory, threads, disk) are available to confirm the "
        "exhausted resource."
    ),
    Category.TIMEOUT: (
        "No telemetry from the called component is available, so it cannot be confirmed whether "
        "it was slow, overloaded or unreachable."
    ),
    Category.DOWNSTREAM_DEPENDENCY: (
        "No telemetry from the downstream dependency is available, so its failure cannot be "
        "confirmed as the definitive cause."
    ),
    Category.APPLICATION_CODE: (
        "The code location comes from the stack trace; the input data that triggered the error "
        "is not available."
    ),
    Category.UNKNOWN: (
        "More context is needed, for example stack traces, logs around the failure or telemetry "
        "from the components involved."
    ),
}


@dataclass(frozen=True)
class CandidateContext:
    incident: IncidentRecord
    features: list[EventFeatures]
    assessments: list[CategoryAssessment]
    recurrence: Recurrence
    hosts: HostDistribution
    top_frame: Share | None
    events_with_stack_trace: int
    preceding: list[Link]
    profiles: dict[UUID, IncidentProfile]

    @property
    def total(self) -> int:
        return len(self.features)

    @property
    def location(self) -> str | None:
        return self.top_frame.value if self.top_frame else None

    @property
    def exception(self) -> str | None:
        counts: dict[str, int] = {}
        for item in self.features:
            if item.exception_type:
                counts[item.exception_type] = counts.get(item.exception_type, 0) + 1
        return min(counts.items(), key=lambda kv: (-kv[1], kv[0]))[0] if counts else None

    def assessment(self, category: Category) -> CategoryAssessment | None:
        return next((a for a in self.assessments if a.category is category), None)


@dataclass
class _Draft:
    category: Category
    title: str
    explanation: str
    score: int = 0
    basis: list[str] = field(default_factory=list)
    evidence: list[Evidence] = field(default_factory=list)
    caps: list[tuple[Confidence, str]] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)
    related_incident_ids: list[UUID] = field(default_factory=list)
    related_event_ids: list[UUID] = field(default_factory=list)
    service: str | None = None
    location: str | None = None
    source: str | None = None


def build_candidates(ctx: CandidateContext) -> list[Candidate]:
    drafts = [_rule_draft(a, ctx) for a in ctx.assessments if a.qualifies]
    propagation = _propagation_draft(ctx)
    if propagation:
        assessment = ctx.assessment(propagation.category)
        if assessment:
            drafts = [d for d in drafts if d.category is not propagation.category]
            propagation = _merge(propagation, assessment, ctx)
        drafts.append(propagation)
    if not drafts:
        drafts.append(_unknown_draft(ctx))

    drafts.sort(key=lambda d: (-d.score, CATEGORY_ORDER.get(d.category, len(CATEGORY_ORDER))))
    drafts = drafts[:MAX_CANDIDATES]
    if len(drafts) > 1 and drafts[0].score == drafts[1].score:
        for draft in drafts[:2]:
            draft.caps.append((Confidence.MEDIUM, "another candidate has the same score"))
    return [_finish(rank, draft, ctx) for rank, draft in enumerate(drafts, start=1)]


def _rule_draft(assessment: CategoryAssessment, ctx: CandidateContext) -> _Draft:
    category = assessment.category
    draft = _Draft(
        category=category,
        title=_title(category, ctx, assessment),
        explanation=_explanation(category, ctx),
    )
    total = ctx.total
    _apply_rule_matches(draft, assessment, total)
    matched_events = len(assessment.event_ids)
    if total >= 3 and matched_events * 5 >= total * 4:
        draft.score += 1
        draft.basis.append(
            f"+1 the same evidence appears in {matched_events}/{total} analyzed events"
        )
    _add_location_bonus(draft, ctx)

    if not assessment.counts_for(SignalKind.EXCEPTION_TYPE):
        draft.caps.append(
            (
                Confidence.MEDIUM,
                f"the exception type does not match the {assessment.label} rules",
            )
        )
    draft.limitations.append(_LIMITATIONS[category])
    draft.location = ctx.location
    return draft


def _apply_rule_matches(draft: _Draft, assessment: CategoryAssessment, total: int) -> None:
    """Evidence and points for each rule kind of the category that matched."""
    for kind in SignalKind:
        matched = assessment.count(kind)
        if not matched:
            continue
        value = assessment.most_common(kind) or ""
        draft.evidence.append(
            Evidence(
                signal=kind.value,
                description=_describe_match(kind, assessment.category, value, matched, total),
                matched=matched,
                total=total,
            )
        )
        phrase = _KIND_PHRASES[kind].format(label=assessment.label)
        if assessment.counts_for(kind):
            draft.score += KIND_POINTS[kind]
            draft.basis.append(f"+{KIND_POINTS[kind]} {phrase} in {matched}/{total} events")
        else:
            draft.basis.append(f"+0 {phrase} in only {matched}/{total} events (less than half)")
    draft.related_event_ids = sorted(set(draft.related_event_ids) | assessment.event_ids, key=str)


def _add_location_bonus(draft: _Draft, ctx: CandidateContext) -> None:
    top = ctx.top_frame
    if top and top.total >= 2 and top.count * 5 >= top.total * 4:
        draft.score += 1
        draft.basis.append(
            f"+1 same top application frame in {top.count}/{top.total} events with stack traces"
        )


def _propagation_draft(ctx: CandidateContext) -> _Draft | None:
    """An earlier error in another incident, within the same trace or correlation ID."""
    link = next((link for link in ctx.preceding if link.trace_event_ids), None)
    if not link:
        return None
    upstream = link.incident
    profile = ctx.profiles.get(upstream.id)
    upstream_exception = profile.exception_type if profile else None
    upstream_name = simple_class_name(upstream_exception) if upstream_exception else upstream.title
    traced_total = sum(1 for e in ctx.incident.events if e.trace_id or e.correlation_id)
    linked = len(link.trace_event_ids)
    service = upstream.application.service

    draft = _Draft(
        category=Category.DOWNSTREAM_DEPENDENCY,
        title=f"Failure propagated from {service} ({upstream_name})",
        explanation=(
            f"Events of this incident share trace or correlation IDs with earlier errors in "
            f"{service} ({upstream_exception or upstream.title}). The failure most likely "
            f"started in {service} and propagated to {ctx.incident.application.service}."
        ),
        service=service,
        location=profile.top_frame_display if profile else None,
        source=profile.source if profile else None,
        related_incident_ids=[upstream.id],
        related_event_ids=sorted(link.trace_event_ids, key=str),
    )
    draft.score += 2
    draft.basis.append(
        f"+2 an error in {service} precedes this error within the same trace/correlation ID "
        f"({linked}/{traced_total} events with IDs)"
    )
    if linked >= 2:
        draft.score += 1
        draft.basis.append(f"+1 the trace link repeats in {linked} events")
    if traced_total >= 2 and linked * 5 >= traced_total * 4:
        draft.score += 1
        draft.basis.append("+1 the trace link appears in at least 80% of events with IDs")
    if profile and profile.category is not Category.UNKNOWN:
        draft.score += 1
        draft.basis.append(
            f"+1 the {service} incident is itself classified as {profile.category.value}"
        )
        draft.explanation += (
            f" The {service} incident's own evidence points to {profile.category.value}."
        )
    if linked == 1:
        draft.caps.append((Confidence.MEDIUM, "only one trace links the two incidents"))

    draft.evidence.append(
        Evidence(
            signal="TRACE_CORRELATION",
            description=(
                f"{linked}/{traced_total} events share a trace/correlation ID with an earlier "
                f"{upstream_exception or 'error'} in {service} (median "
                f"{link.to_model().median_gap_seconds:g} s before)"
            ),
            matched=linked,
            total=traced_total,
        )
    )
    if profile and profile.category is not Category.UNKNOWN:
        draft.evidence.append(
            Evidence(
                signal="UPSTREAM_CLASSIFICATION",
                description=(
                    f"The {service} incident is classified as {profile.category.value} by the rules"
                ),
            )
        )
    draft.limitations += [
        "Shared trace IDs show that the requests are connected; they do not prove that the "
        "earlier error caused this one.",
        _LIMITATIONS[Category.DOWNSTREAM_DEPENDENCY],
    ]
    return draft


def _merge(propagation: _Draft, assessment: CategoryAssessment, ctx: CandidateContext) -> _Draft:
    """Adds this incident's own downstream-rule matches to the propagation candidate. The
    repetition and location bonuses of the rule candidate are not added: the trace link has its
    own repetition points."""
    _apply_rule_matches(propagation, assessment, ctx.total)
    return propagation


def _unknown_draft(ctx: CandidateContext) -> _Draft:
    exception = ctx.exception
    name = simple_class_name(exception) if exception else None
    draft = _Draft(
        category=Category.UNKNOWN,
        title=f"Insufficient evidence to classify {name}"
        if name
        else "Insufficient evidence to determine a cause",
        explanation=_EXPLANATIONS[Category.UNKNOWN],
        location=ctx.location,
    )
    draft.basis.append("+0 no category rule matches with at least 2 points")
    weak = [a for a in ctx.assessments if not a.qualifies]
    for assessment in weak:
        draft.evidence.append(
            Evidence(
                signal="WEAK_MATCH",
                description=f"Weak {assessment.label} evidence ({assessment.points} point) is not "
                f"enough for a candidate",
            )
        )
    if exception:
        draft.evidence.append(
            Evidence(
                signal="EXCEPTION_TYPE",
                description=f"{exception} does not match any category rule",
            )
        )
    draft.caps.append((Confidence.LOW, "no category is supported by the evidence"))
    draft.limitations.append(_LIMITATIONS[Category.UNKNOWN])
    return draft


def _finish(rank: int, draft: _Draft, ctx: CandidateContext) -> Candidate:
    incident = ctx.incident
    score = min(draft.score, confidence.MAX_SCORE)
    level = confidence.band(score)
    basis = list(draft.basis)
    if draft.score > confidence.MAX_SCORE:
        basis.append(
            f"= {draft.score} points, counted as the maximum {score} of {confidence.MAX_SCORE}: "
            f"{level.value}"
        )
    else:
        basis.append(f"= {score} of {confidence.MAX_SCORE} points: {level.value}")
    caps = list(draft.caps)
    if incident.occurrence_count <= 1:
        caps.append((Confidence.MEDIUM, "single occurrence"))
    if ctx.events_with_stack_trace == 0:
        caps.append((Confidence.MEDIUM, "no stack trace available"))
    for maximum, reason in caps:
        capped = confidence.cap(level, maximum)
        if capped is not level:
            basis.append(f"capped at {capped.value}: {reason}")
            level = capped

    evidence = list(draft.evidence) + _context_evidence(draft, ctx)
    limitations = list(draft.limitations)
    if ctx.events_with_stack_trace == 0:
        limitations.append("No stack trace was available, so the code location is unknown.")
    return Candidate(
        rank=rank,
        category=draft.category,
        title=draft.title,
        confidence=level,
        score=score,
        max_score=confidence.MAX_SCORE,
        confidence_basis=basis,
        explanation=draft.explanation,
        evidence=evidence,
        affected_component=AffectedComponent(
            service=draft.service or incident.application.service,
            environment=incident.application.environment,
            location=draft.location,
            source=draft.source if draft.service else _source(ctx),
        ),
        related_incident_ids=draft.related_incident_ids,
        related_event_ids=draft.related_event_ids[:MAX_EVENT_IDS],
        limitations=limitations,
    )


def _context_evidence(draft: _Draft, ctx: CandidateContext) -> list[Evidence]:
    """Facts shown with every candidate; they explain the situation but add no points."""
    incident = ctx.incident
    items = [
        Evidence(
            signal="RECURRENCE",
            description=ctx.recurrence.description,
            matched=incident.occurrence_count,
        ),
        Evidence(
            signal="SERVICE",
            description=(
                f"Failures affect {incident.application.service} in "
                f"{incident.application.environment}"
            ),
        ),
    ]
    if ctx.top_frame:
        items.append(
            Evidence(
                signal="CODE_LOCATION",
                description=(
                    f"Top application frame of {incident.application.service}: "
                    f"{ctx.top_frame.value} "
                    f"in {ctx.top_frame.count}/"
                    f"{ctx.top_frame.total} events with stack traces"
                ),
                matched=ctx.top_frame.count,
                total=ctx.top_frame.total,
            )
        )
    hosts = ctx.hosts
    if hosts.concentrated:
        top = hosts.top[0]
        events = "Both analyzed events" if top.count == 2 else f"All {top.count} analyzed events"
        items.append(
            Evidence(
                signal="HOST",
                description=f"{events} with a host came from {top.value}",
                matched=top.count,
                total=top.total,
            )
        )
    elif hosts.distinct > 1:
        items.append(
            Evidence(
                signal="HOST",
                description=(
                    f"Events came from {hosts.distinct} hosts, so the failure is not limited "
                    "to one host"
                ),
            )
        )
    return items


def _source(ctx: CandidateContext) -> str | None:
    sources = [f.source for f in ctx.features if f.source]
    return min(set(sources), key=lambda s: (-sources.count(s), s)) if sources else None


def _title(category: Category, ctx: CandidateContext, assessment: CategoryAssessment) -> str:
    location = ctx.location
    at = f" in {location}" if location else ""
    exception = ctx.exception
    name = simple_class_name(exception) if exception else None
    if category is Category.TIMEOUT:
        downstream = ctx.assessment(Category.DOWNSTREAM_DEPENDENCY)
        if downstream and (
            downstream.count(SignalKind.COMPONENT) or downstream.count(SignalKind.MESSAGE)
        ):
            return "Timeout waiting for a downstream dependency" + (
                f" called from {location}" if location else ""
            )
        return "Operation timed out" + at
    if category is Category.DATABASE:
        message = assessment.most_common(SignalKind.MESSAGE) or ""
        if any(word in message for word in ("connection", "hikari", "pool", "refused", "server")):
            return "Database connectivity or connection-pool problem" + (
                f" (accessed from {location})" if location else ""
            )
        return "Database operation failure" + at
    if category is Category.DOWNSTREAM_DEPENDENCY:
        return "Downstream dependency failure" + (f" called from {location}" if location else "")
    if category is Category.APPLICATION_CODE:
        root = next(
            (f.trace.root_cause for f in ctx.features if f.trace.raised_in_application_code), None
        )
        place = root.frames[0].display if root else location
        return f"{name or 'Programming error'} in application code" + (
            f" at {place}" if place else ""
        )
    if category is Category.AUTHENTICATION:
        return "Authentication or authorization failure" + (f" ({name})" if name else "")
    if category is Category.SERIALIZATION:
        return "Malformed or incompatible data (parsing/serialization failure)" + at
    if category is Category.CONFIGURATION:
        message = assessment.most_common(SignalKind.MESSAGE)
        return "Configuration problem" + (f": {message}" if message else "") + at
    if category is Category.RESOURCE_EXHAUSTION:
        message = assessment.most_common(SignalKind.MESSAGE)
        return "Resource exhaustion" + (
            f" ({message})" if message else (f" ({name})" if name else "")
        )
    return "Insufficient evidence to determine a cause"


def _explanation(category: Category, ctx: CandidateContext) -> str:
    text = _EXPLANATIONS[category]
    if category is Category.TIMEOUT:
        downstream = ctx.assessment(Category.DOWNSTREAM_DEPENDENCY)
        if downstream and downstream.count(SignalKind.COMPONENT) and ctx.location:
            text += (
                f" Network-client frames or a client/gateway class in the stack trace suggest that "
                f"the slow component is a dependency called from {ctx.location}."
            )
    return text


def _describe_match(
    kind: SignalKind, category: Category, value: str, matched: int, total: int
) -> str:
    events = f"{matched}/{total} events"
    if kind is SignalKind.EXCEPTION_TYPE:
        return f"{value} in {events}"
    if kind is SignalKind.MESSAGE:
        return f'Messages contain "{value}" in {events}'
    if category is Category.APPLICATION_CODE:
        return f"Exception raised directly in application code ({value}) in {events}"
    return f"Stack frames or source include {value} in {events}"
