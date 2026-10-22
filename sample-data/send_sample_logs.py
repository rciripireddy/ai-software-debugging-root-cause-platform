"""Send the demo log events in application-logs.jsonl to the ingestion API.

Usage:
    python sample-data/send_sample_logs.py [--url http://localhost:8081]
        [--application demo-shop] [--incidents-url http://localhost:8082] [--no-wait]
        [--analyze] [--analysis-url http://localhost:8000]

After sending, the script waits until incident-service has correlated the ERROR events
into incidents (through Kafka) and prints them. With --analyze, it then analyzes each
incident through analysis-service and prints the most likely cause it suggests. Uses only
the Python standard library.
Every run stores the events again, as new rows.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SAMPLE_FILE = Path(__file__).with_name("application-logs.jsonl")
CORRELATED_SEVERITIES = {"ERROR", "FATAL"}


def post_json(url: str, body: dict | None) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def wait_for_incidents(base_url: str, application: str, expected: int) -> list:
    """Polls the incident API until `expected` occurrences have been correlated."""
    query = urllib.parse.urlencode({"application": application, "size": 100})
    url = f"{base_url.rstrip('/')}/api/v1/incidents?{query}"
    deadline = time.monotonic() + 30
    incidents = []
    while time.monotonic() < deadline:
        incidents = get_json(url)["items"]
        if sum(incident["occurrenceCount"] for incident in incidents) >= expected:
            break
        time.sleep(1)
    return incidents


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--url", default="http://localhost:8081", help="ingestion-service base URL"
    )
    parser.add_argument(
        "--application",
        default="demo-shop",
        help="application name to send the demo events under",
    )
    parser.add_argument(
        "--incidents-url",
        default="http://localhost:8082",
        help="incident-service base URL",
    )
    parser.add_argument(
        "--no-wait", action="store_true", help="do not wait for incidents"
    )
    parser.add_argument(
        "--analyze",
        action="store_true",
        help="analyze each incident through analysis-service",
    )
    parser.add_argument(
        "--analysis-url",
        default="http://localhost:8000",
        help="analysis-service base URL",
    )
    args = parser.parse_args()

    events = [
        json.loads(line)
        for line in SAMPLE_FILE.read_text(encoding="utf-8").splitlines()
        if line
    ]
    for event in events:
        event["application"] = args.application
        try:
            result = post_json(f"{args.url.rstrip('/')}/api/v1/logs", event)
        except urllib.error.HTTPError as error:
            print(
                f"Rejected ({error.code}): {error.read().decode('utf-8')}",
                file=sys.stderr,
            )
            return 1
        except urllib.error.URLError as error:
            print(f"Cannot reach {args.url}: {error.reason}", file=sys.stderr)
            return 1
        print(
            f"{result['id']}  {event['severity']:<5}  {event['service']:<17}  "
            f"fingerprint {result['fingerprint'][:12]}"
        )
    print(f"Sent {len(events)} demo events for application '{args.application}'.")

    if args.no_wait:
        return 0
    expected = sum(1 for event in events if event["severity"] in CORRELATED_SEVERITIES)
    try:
        incidents = wait_for_incidents(args.incidents_url, args.application, expected)
    except urllib.error.URLError as error:
        print(f"Cannot reach {args.incidents_url}: {error}", file=sys.stderr)
        return 1
    print(f"\nIncidents for '{args.application}' (created automatically via Kafka):")
    for incident in incidents:
        print(
            f"  {incident['occurrenceCount']:>3} x  {incident['application']['service']:<17}"
            f"  {incident['status']:<6}  {incident['title'][:70]}"
        )

    if not args.analyze:
        return 0
    print("\nAnalysis (rule-based; potential causes, not confirmed root causes):")
    for incident in incidents:
        url = f"{args.analysis_url.rstrip('/')}/api/v1/analysis/incidents/{incident['id']}"
        try:
            primary = post_json(url, None)["primaryCandidate"]
        except urllib.error.URLError as error:
            print(f"Cannot analyze via {args.analysis_url}: {error}", file=sys.stderr)
            return 1
        print(
            f"  {incident['application']['service']:<17}  {primary['category']:<21}"
            f"  {primary['confidence']:<6}  {primary['title'][:80]}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
