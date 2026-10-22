"""Runs the analysis engine directly on the scenarios (no services, no database)."""

from datetime import UTC, datetime

from analysis_service.analysis.engine import AnalysisSettings, analyze
from analysis_service.models.analysis import AnalysisReport
from evaluation.dataset import build_offline_incidents, load_scenarios
from evaluation.metrics import Prediction

GENERATED_AT = datetime(2026, 10, 20, tzinfo=UTC)


def run_offline() -> tuple[dict[str, Prediction], dict[str, AnalysisReport]]:
    incidents = build_offline_incidents(load_scenarios())
    scenario_of = {incident.id: scenario for scenario, incident in incidents.items()}
    pool = list(incidents.values())
    reports: dict[str, AnalysisReport] = {}
    predictions: dict[str, Prediction] = {}
    for scenario, incident in incidents.items():
        report = analyze(incident, pool, AnalysisSettings(), GENERATED_AT)
        primary = report.candidates[0]
        reports[scenario] = report
        predictions[scenario] = Prediction(
            category=primary.category.value,
            confidence=primary.confidence.value,
            title=primary.title,
            related_scenarios=frozenset(
                scenario_of[r.incident_id] for r in report.related_incidents
            ),
        )
    return predictions, reports
