"""Labelled synthetic scenarios (sample-data/analysis-scenarios) produce the expected results.

The scenarios were written together with the rules, so this is a regression test of the rules'
intended behavior, not a measure of accuracy on real incidents.
"""

import json

from evaluation.dataset import SCENARIOS_FILE, build_offline_incidents, load_labels, load_scenarios
from evaluation.metrics import evaluate, format_report
from evaluation.offline import run_offline


def test_every_scenario_gets_its_expected_primary_category():
    predictions, _ = run_offline()
    evaluation = evaluate(predictions, load_labels())

    wrong = [(o.scenario, o.expected, o.predicted) for o in evaluation.outcomes if not o.correct]
    assert wrong == []
    assert len(evaluation.outcomes) == 16


def test_related_incident_pairs_match_the_labels():
    predictions, _ = run_offline()
    evaluation = evaluate(predictions, load_labels())

    assert evaluation.predicted_pairs == evaluation.expected_pairs
    assert evaluation.related_precision == 1.0


def test_unrelated_scenarios_are_never_strongly_related():
    _, reports = run_offline()
    labels = load_labels()
    incidents = build_offline_incidents(load_scenarios())
    scenario_of = {incident.id: scenario for scenario, incident in incidents.items()}

    for scenario, report in reports.items():
        for related in report.related_incidents:
            other = scenario_of[related.incident_id]
            if frozenset((scenario, other)) not in labels.related_pairs:
                raise AssertionError(f"{scenario} unexpectedly related to {other}")


def test_runtime_input_contains_no_labels():
    text = SCENARIOS_FILE.read_text(encoding="utf-8")
    document = json.loads(text)

    for label in (
        "expectedCategory",
        "relatedPairs",
        *(o for o in load_labels().expected_category.values()),
    ):
        assert label not in text
    assert all(set(s) == {"id", "events"} for s in document["scenarios"])


def test_report_states_that_the_data_is_synthetic():
    predictions, _ = run_offline()

    report = format_report(evaluate(predictions, load_labels()), "offline")
    assert "not accuracy on production incidents" in report
