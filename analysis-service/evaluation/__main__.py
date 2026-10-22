"""Evaluation command line.

    python -m evaluation                 # offline: analysis engine only, no services needed
    python -m evaluation --live          # through the running stack (ingestion -> Kafka ->
                                         # incident-service -> analysis API)

Live mode sends the scenario events under a new application name, waits until incident-service
has correlated them, analyzes every resulting incident through the analysis API (which stores
the results) and compares the stored primary categories with the labels.

Live mode needs INGESTION_API_KEY and an ADMIN account (RCP_USERNAME / RCP_PASSWORD), from the
environment or, for local development, from the repository's .env file (the bootstrap admin).
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from http.cookies import SimpleCookie
from pathlib import Path
from typing import Any

from evaluation.dataset import INCIDENT_SEVERITIES, load_labels, load_scenarios
from evaluation.metrics import Prediction, evaluate, format_report

ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


def _env_file_values() -> dict[str, str]:
    if not ENV_FILE.is_file():
        return {}
    values = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def _credential(values: dict[str, str], name: str, file_name: str | None = None) -> str:
    value = os.environ.get(name) or values.get(file_name or name)
    if not value:
        raise SystemExit(f"Set {name} for live mode (see .env.example).")
    return value


def _request(
    method: str, url: str, headers: dict[str, str], body: dict[str, Any] | None = None
) -> Any:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Correlation-Id": "analysis-evaluation",
            **headers,
        },
    )
    for _attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            # Manual analyses are rate limited per user; wait as long as the service asks.
            if exc.code != 429:
                raise
            time.sleep(int(exc.headers.get("Retry-After", "5")))
    raise SystemExit(f"Still rate limited after several attempts: {method} {url}")


def _sign_in(incident_url: str, username: str, password: str) -> dict[str, str]:
    request = urllib.request.Request(
        f"{incident_url}/api/v1/auth/login",
        data=json.dumps({"username": username, "password": password}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        cookies: SimpleCookie = SimpleCookie()
        for header in response.headers.get_all("Set-Cookie") or []:
            cookies.load(header)
    return {"Authorization": f"Bearer {cookies['rcp_session'].value}"}


def run_live(args: argparse.Namespace) -> dict[str, Prediction]:
    values = _env_file_values()
    producer = {"X-Api-Key": _credential(values, "INGESTION_API_KEY")}
    user = _sign_in(
        args.incident_url,
        _credential(values, "RCP_USERNAME", "AUTH_BOOTSTRAP_ADMIN_USERNAME"),
        _credential(values, "RCP_PASSWORD", "AUTH_BOOTSTRAP_ADMIN_PASSWORD"),
    )
    application = args.application or f"analysis-eval-{uuid.uuid4().hex[:8]}"
    scenarios = load_scenarios()
    event_scenario: dict[str, str] = {}
    print(f"Sending {sum(map(len, scenarios.values()))} events as application '{application}'...")
    for scenario, events in scenarios.items():
        for event in events:
            created = _request(
                "POST",
                f"{args.ingestion_url}/api/v1/logs",
                producer,
                {**event, "application": application},
            )
            if event["severity"] in INCIDENT_SEVERITIES:
                event_scenario[created["id"]] = scenario

    print("Waiting for incident-service to correlate the events...")
    incident_scenario: dict[str, str] = {}
    deadline = time.monotonic() + args.timeout
    while True:
        page = _request(
            "GET", f"{args.incident_url}/api/v1/incidents?application={application}&size=100", user
        )
        assigned: set[str] = set()
        incident_scenario.clear()
        for item in page["items"]:
            detail = _request("GET", f"{args.incident_url}/api/v1/incidents/{item['id']}", user)
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
        analysis = _request(
            "POST", f"{args.analysis_url}/api/v1/analysis/incidents/{incident_id}", user
        )
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
