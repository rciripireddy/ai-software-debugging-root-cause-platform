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

Credentials (see docs/security.md), each taken from the environment variable or, for local
development, from the repository's .env file:
    INGESTION_API_KEY            sends the events (X-Api-Key)
    RCP_USERNAME / RCP_PASSWORD  signs in to read incidents and run analyses; defaults to
                                 AUTH_BOOTSTRAP_ADMIN_USERNAME / _PASSWORD from .env
They are never printed.
"""

import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookies import SimpleCookie
from pathlib import Path

SAMPLE_FILE = Path(__file__).with_name("application-logs.jsonl")
ENV_FILE = Path(__file__).resolve().parents[1] / ".env"
CORRELATED_SEVERITIES = {"ERROR", "FATAL"}
SESSION_COOKIE = "rcp_session"


def read_env_file(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def setting(env_file: dict[str, str], name: str, file_name: str | None = None) -> str | None:
    """An environment variable, else the value from .env (under `file_name` if given)."""
    return os.environ.get(name) or env_file.get(file_name or name) or None


def post_json(url: str, body: dict | None, headers: dict[str, str]) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def get_json(url: str, headers: dict[str, str]) -> dict:
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def sign_in(incidents_url: str, username: str, password: str) -> dict[str, str]:
    """Signs in and returns the header that authenticates further requests (Bearer token)."""
    request = urllib.request.Request(
        f"{incidents_url.rstrip('/')}/api/v1/auth/login",
        data=json.dumps({"username": username, "password": password}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        cookies = SimpleCookie()
        for header in response.headers.get_all("Set-Cookie") or []:
            cookies.load(header)
    return {"Authorization": f"Bearer {cookies[SESSION_COOKIE].value}"}


def wait_for_incidents(
    base_url: str, application: str, expected: int, auth: dict[str, str]
) -> list:
    """Polls the incident API until `expected` occurrences have been correlated."""
    query = urllib.parse.urlencode({"application": application, "size": 100})
    url = f"{base_url.rstrip('/')}/api/v1/incidents?{query}"
    deadline = time.monotonic() + 30
    incidents = []
    while time.monotonic() < deadline:
        incidents = get_json(url, auth)["items"]
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
    parser.add_argument(
        "--env-file", type=Path, default=ENV_FILE, help="local .env file to read credentials from"
    )
    args = parser.parse_args()
    env_file = read_env_file(args.env_file)
    api_key = setting(env_file, "INGESTION_API_KEY")
    if not api_key:
        print("Set INGESTION_API_KEY (see .env.example).", file=sys.stderr)
        return 1

    events = [
        json.loads(line)
        for line in SAMPLE_FILE.read_text(encoding="utf-8").splitlines()
        if line
    ]
    for event in events:
        event["application"] = args.application
        try:
            result = post_json(
                f"{args.url.rstrip('/')}/api/v1/logs", event, {"X-Api-Key": api_key}
            )
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
    username = setting(env_file, "RCP_USERNAME", "AUTH_BOOTSTRAP_ADMIN_USERNAME")
    if not username:
        print("Set RCP_USERNAME and RCP_PASSWORD to list the incidents.", file=sys.stderr)
        return 0
    password = setting(env_file, "RCP_PASSWORD", "AUTH_BOOTSTRAP_ADMIN_PASSWORD")
    if not password:
        password = getpass.getpass(f"Password for {username}: ")
    try:
        auth = sign_in(args.incidents_url, username, password)
        incidents = wait_for_incidents(args.incidents_url, args.application, expected, auth)
    except urllib.error.HTTPError as error:
        print(f"Sign-in or incident request failed (HTTP {error.code}).", file=sys.stderr)
        return 1
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
            primary = post_json(url, None, auth)["primaryCandidate"]
        except urllib.error.HTTPError as error:
            print(
                f"Analysis refused (HTTP {error.code}); it needs an ADMIN account.",
                file=sys.stderr,
            )
            return 1
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
