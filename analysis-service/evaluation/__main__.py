"""Evaluation command line.

    python -m evaluation                 # offline: analysis engine only, no services needed
    python -m evaluation --live          # through the running stack (ingestion -> Kafka ->
                                         # incident-service -> analysis API)

Live mode sends the scenario events under a new application name, waits until incident-service
has correlated them, analyzes every resulting incident through the analysis API (which stores
the results) and compares the stored primary categories with the labels.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any

from evaluation.dataset import INCIDENT_SEVERITIES, load_labels, load_scenarios
from evaluation.metrics import Prediction, evaluate, format_report


def _request(method: str, url: str, body: dict[str, Any] | None = None) -> Any:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Correlation-Id": "analysis-evaluation",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def run_live(args: argparse.Namespace) -> dict[str, Prediction]:
    application = args.application or f"analysis-eval-{uuid.uuid4().hex[:8]}"
    scenarios = load_scenarios()
    event_scenario: dict[str, str] = {}
    print(f"Sending {sum(map(len, scenarios.values()))} events as application '{application}'...")
    for scenario, events in scenarios.items():
        for event in events:
            created = _request(
                "POST", f"{args.ingestion_url}/api/v1/logs", {**event, "application": application}
            )
            if event["severity"] in INCIDENT_SEVERITIES:
                event_scenario[created["id"]] = scenario

    print("Waiting for incident-service to correlate the events...")
    incident_scenario: dict[str, str] = {}
    deadline = time.monotonic() + args.timeout
    while True:
        page = _request(
            "GET", f"{args.incident_url}/api/v1/incidents?application={application}&size=100"
        )
        assigned: set[str] = set()
        incident_scenario.clear()
        for item in page["items"]:
            detail = _request("GET", f"{args.incident_url}/api/v1/incidents/{item['id']}")
            scenarios_seen = {event_scenario.get(e["id"]) for e in detail["recentEvents"]} - {None}
            if len(scenarios_seen) != 1:
                raise SystemExit(f"Incident {item['id']} mixes scenarios {sorted(scenarios_seen)}")
            incident_scenario[item["id"]] = scenarios_seen.pop()
            assigned |= {e["id"] for e in detail["recentEvents"]}
        if assigned >= event_scenario.keys():
            break
        if time.monotonic() > deadline:
            raise SystemExit(
                f"Timed out: {len(event_scenario.keys() - assigned)} events not correlated"
            )
        time.sleep(1)
    if len(set(incident_scenario.values())) != len(incident_scenario):
        raise SystemExit("A scenario produced more than one incident")
    print(f"{len(incident_scenario)} incidents created. Analyzing...")

    predictions: dict[str, Prediction] = {}
    for incident_id, scenario in incident_scenario.items():
        analysis = _request("POST", f"{args.analysis_url}/api/v1/analysis/incidents/{incident_id}")
        primary = analysis["primaryCandidate"]
        predictions[scenario] = Prediction(
            category=primary["category"],
            confidence=primary["confidence"],
            title=primary["title"],
            related_scenarios=frozenset(
                incident_scenario[r["incidentId"]]
                for r in analysis["relatedIncidents"]
                if r["incidentId"] in incident_scenario
            ),
        )
    if args.show_incidents:
        for incident_id, scenario in sorted(incident_scenario.items(), key=lambda kv: kv[1]):
            print(f"  {scenario}  incident {incident_id}")
    return predictions


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--live", action="store_true", help="evaluate through the running services")
    parser.add_argument("--application", help="application name for live mode (default: random)")
    parser.add_argument("--ingestion-url", default="http://localhost:8081")
    parser.add_argument("--incident-url", default="http://localhost:8082")
    parser.add_argument("--analysis-url", default="http://localhost:8000")
    parser.add_argument("--timeout", type=int, default=60, help="seconds to wait for correlation")
    parser.add_argument("--show-incidents", action="store_true", help="print incident IDs (live)")
    args = parser.parse_args()

    if args.live:
        try:
            predictions = run_live(args)
        except urllib.error.URLError as exc:
            print(f"Request failed: {exc}", file=sys.stderr)
            return 1
        mode = "live, through the running services"
    else:
        from evaluation.offline import run_offline

        predictions, _ = run_offline()
        mode = "offline, analysis engine only"
    print(format_report(evaluate(predictions, load_labels()), mode))
    return 0


if __name__ == "__main__":
    sys.exit(main())
