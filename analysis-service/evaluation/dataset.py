"""Loads the synthetic scenarios and builds incidents from them without running the backend."""

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import UUID, uuid5

from analysis_service.analysis.normalization import normalize_message
from analysis_service.analysis.stacktrace import parse_stack_trace
from analysis_service.models.records import ApplicationRef, EventRecord, IncidentRecord

DATASET_DIR = Path(__file__).resolve().parents[2] / "sample-data" / "analysis-scenarios"
SCENARIOS_FILE = DATASET_DIR / "scenarios.json"
LABELS_FILE = DATASET_DIR / "labels.json"
APPLICATION = "analysis-eval"
INCIDENT_SEVERITIES = ("ERROR", "FATAL")
_NAMESPACE = UUID("6f1c2d3e-4a5b-4c6d-8e7f-90a1b2c3d4e5")


@dataclass(frozen=True)
class Labels:
    expected_category: dict[str, str]
    names: dict[str, str]
    related_pairs: frozenset[frozenset[str]]


def load_scenarios(path: Path = SCENARIOS_FILE) -> dict[str, list[dict[str, Any]]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    return {scenario["id"]: scenario["events"] for scenario in document["scenarios"]}


def load_labels(path: Path = LABELS_FILE) -> Labels:
    document = json.loads(path.read_text(encoding="utf-8"))
    scenarios = document["scenarios"]
    return Labels(
        expected_category={key: value["expectedCategory"] for key, value in scenarios.items()},
        names={key: value["name"] for key, value in scenarios.items()},
        related_pairs=frozenset(frozenset(pair) for pair in document["relatedPairs"]),
    )


def offline_fingerprint(event: dict[str, Any]) -> str:
    """Approximation of ingestion-service's fingerprint (service, exception type, normalized
    message, top five frames without line numbers), used only to build incidents offline."""
    frames = [frame.key for frame in parse_stack_trace(event.get("stackTrace")).all_frames][:5]
    parts = [
        event["service"],
        event.get("exceptionType") or "",
        normalize_message(event["message"]),
        *frames,
    ]
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()


def _timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def build_offline_incidents(
    scenarios: dict[str, list[dict[str, Any]]],
) -> dict[str, IncidentRecord]:
    """One incident per scenario, shaped like incident-service's (events newest first).

    Each scenario's events share a fingerprint by construction; this is checked so that the
    offline incidents match what the running pipeline creates.
    """
    incidents: dict[str, IncidentRecord] = {}
    for scenario_id, raw_events in scenarios.items():
        events = [e for e in raw_events if e["severity"] in INCIDENT_SEVERITIES]
        fingerprints = {offline_fingerprint(e) for e in events}
        if len(fingerprints) != 1:
            raise ValueError(f"Scenario {scenario_id} does not form a single incident")
        first = events[0]
        records = sorted(
            (
                EventRecord(
                    id=uuid5(_NAMESPACE, f"{scenario_id}:event:{index}"),
                    timestamp=_timestamp(e["timestamp"]),
                    severity=e["severity"],
                    message=e["message"],
                    exception_type=e.get("exceptionType"),
                    host=e.get("host"),
                    trace_id=e.get("traceId"),
                    correlation_id=e.get("correlationId"),
                    stack_trace=e.get("stackTrace"),
                    source=e.get("source"),
                    details_loaded=True,
                )
                for index, e in enumerate(events)
            ),
            key=lambda record: (record.timestamp, str(record.id)),
            reverse=True,
        )
        exception = first.get("exceptionType")
        incidents[scenario_id] = IncidentRecord(
            id=uuid5(_NAMESPACE, f"{scenario_id}:incident"),
            title=f"{exception}: {first['message']}" if exception else first["message"],
            status="OPEN",
            severity=max((e["severity"] for e in events), key=INCIDENT_SEVERITIES.index),
            application=ApplicationRef(
                id=uuid5(_NAMESPACE, f"{first['service']}:{first['environment']}"),
                name=APPLICATION,
                service=first["service"],
                environment=first["environment"],
            ),
            occurrence_count=len(records),
            first_seen_at=records[-1].timestamp,
            last_seen_at=records[0].timestamp,
            fingerprint=fingerprints.pop(),
            events=tuple(records),
        )
    return incidents
