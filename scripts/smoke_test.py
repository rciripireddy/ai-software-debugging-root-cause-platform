"""End-to-end smoke test of a running stack, entirely through the dashboard's proxy.

Usage:
    python scripts/smoke_test.py --env-file <file> [--url http://127.0.0.1:3000]

CI runs it against a fresh production-style stack (docker-compose.yml only) started with
throwaway credentials from scripts/ci/generate-env.sh; see docs/ci.md. It submits one ERROR
event under a unique application name, so it leaves one small incident and one analysis behind
when run against a stack that is kept.

Credentials are read from the environment file and never printed:
    INGESTION_API_KEY                                               submits the event
    AUTH_BOOTSTRAP_ADMIN_USERNAME / AUTH_BOOTSTRAP_ADMIN_PASSWORD   signs in, runs the analysis
    AUTH_BOOTSTRAP_VIEWER_USERNAME / AUTH_BOOTSTRAP_VIEWER_PASSWORD optional: role check
Uses only the Python standard library.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from email.message import Message
from http.cookies import SimpleCookie
from pathlib import Path

SESSION_COOKIE = "rcp_session"
INCIDENT_TIMEOUT_SECONDS = 120


class SmokeTestFailure(Exception):
    pass


class Client:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, Message, bytes]:
        """Returns (status, headers, body); HTTP error statuses are returned, not raised."""
        request = urllib.request.Request(
            self.base_url + path,
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            headers={"Content-Type": "application/json", **(headers or {})},
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    def expect(self, method: str, path: str, status: int, **kwargs) -> tuple[Message, bytes]:
        actual, headers, body = self.request(method, path, **kwargs)
        if actual != status:
            raise SmokeTestFailure(f"{method} {path}: expected HTTP {status}, got {actual}")
        return headers, body

    def json(self, method: str, path: str, status: int, **kwargs) -> dict:
        return json.loads(self.expect(method, path, status, **kwargs)[1])

    def sign_in(self, username: str, password: str) -> tuple[dict[str, str], dict]:
        """Signs in; returns the header that authenticates further requests and the session."""
        headers, body = self.expect(
            "POST",
            "/api/incident/api/v1/auth/login",
            200,
            body={"username": username, "password": password},
        )
        cookies = SimpleCookie()
        for header in headers.get_all("Set-Cookie") or []:
            cookies.load(header)
        if SESSION_COOKIE not in cookies:
            raise SmokeTestFailure("sign-in response has no session cookie")
        return {"Authorization": f"Bearer {cookies[SESSION_COOKIE].value}"}, json.loads(body)


def read_env_file(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeTestFailure(message)


def run(client: Client, env: dict[str, str]) -> None:
    def check(description: str) -> None:
        print(f"ok: {description}", flush=True)

    # Dashboard, served by nginx with its security headers.
    headers, body = client.expect("GET", "/", 200)
    require(b'<div id="root">' in body, "the dashboard page does not contain the React root")
    require(headers.get("X-Content-Type-Options") == "nosniff", "security headers missing")
    check("dashboard is served with security headers")

    # Readiness of each service through the proxy.
    for path in (
        "/api/ingestion/actuator/health/readiness",
        "/api/incident/actuator/health/readiness",
        "/api/analysis/health/readiness",
    ):
        status = client.json("GET", path, 200).get("status")
        require(status == "UP", f"{path}: status {status}")
    check("ingestion-service, incident-service and analysis-service are ready")

    # Metrics stay internal (Prometheus scrapes them on the monitoring network).
    for path in ("/api/ingestion/actuator/prometheus", "/api/analysis/metrics"):
        client.expect("GET", path, 404)
    check("metrics endpoints are not reachable through the proxy")

    # Authentication.
    client.expect("GET", "/api/incident/api/v1/incidents", 401)
    admin_name = env["AUTH_BOOTSTRAP_ADMIN_USERNAME"]
    client.expect(
        "POST",
        "/api/incident/api/v1/auth/login",
        401,
        body={"username": admin_name, "password": "not-the-password"},
    )
    admin, session = client.sign_in(admin_name, env["AUTH_BOOTSTRAP_ADMIN_PASSWORD"])
    require(session["user"]["role"] == "ADMIN", "the bootstrap account is not an ADMIN")
    me = client.json("GET", "/api/incident/api/v1/auth/me", 200, headers=admin)
    require(me["user"]["username"] == admin_name, "/auth/me returned another user")
    check("sign-in works; anonymous requests and a wrong password are refused")

    # One ERROR event, under an application name no other run uses.
    application = f"ci-smoke-{uuid.uuid4().hex[:12]}"
    event = {
        "application": application,
        "service": "checkout-service",
        "environment": "ci",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "severity": "ERROR",
        "message": "Payment authorization failed",
        "exceptionType": "java.net.SocketTimeoutException",
        "stackTrace": "java.net.SocketTimeoutException: Read timed out\n"
        "\tat com.shop.payment.GatewayClient.authorize(GatewayClient.java:74)",
        "source": "com.shop.payment.GatewayClient",
    }
    client.expect("POST", "/api/ingestion/api/v1/logs", 401, body=event)
    created = client.json(
        "POST",
        "/api/ingestion/api/v1/logs",
        201,
        body=event,
        headers={"X-Api-Key": env["INGESTION_API_KEY"]},
    )
    check(f"event accepted (without an API key: refused) under application {application}")

    # The event travels through the outbox and Kafka to incident-service.
    query = urllib.parse.urlencode({"application": application})
    deadline = time.monotonic() + INCIDENT_TIMEOUT_SECONDS
    started = time.monotonic()
    incidents: list = []
    while time.monotonic() < deadline:
        incidents = client.json(
            "GET", f"/api/incident/api/v1/incidents?{query}", 200, headers=admin
        )["items"]
        if incidents:
            break
        time.sleep(2)
    require(len(incidents) == 1, f"no incident within {INCIDENT_TIMEOUT_SECONDS} s")
    incident = incidents[0]
    require(incident["occurrenceCount"] == 1, "unexpected occurrence count")
    check(
        f"incident created from the event in {time.monotonic() - started:.0f} s "
        f"(fingerprint {created['fingerprint'][:12]})"
    )

    # Analysis: stored as a revision, then read back.
    analysis_path = f"/api/analysis/api/v1/analysis/incidents/{incident['id']}"
    analysis = client.json("POST", analysis_path, 201, headers=admin)
    require(analysis.get("primaryCandidate") is not None, "the analysis has no primary candidate")
    stored = client.json("GET", analysis_path, 200, headers=admin)
    require(stored["id"] == analysis["id"], "the stored analysis differs from the one just run")
    check(
        f"analysis stored: {analysis['primaryCandidate']['category']} "
        f"({analysis['primaryCandidate']['confidence']} confidence)"
    )

    # A viewer can read but not run analyses.
    viewer_name = env.get("AUTH_BOOTSTRAP_VIEWER_USERNAME")
    if viewer_name:
        viewer, session = client.sign_in(viewer_name, env["AUTH_BOOTSTRAP_VIEWER_PASSWORD"])
        require(session["user"]["role"] == "VIEWER", "the viewer account is not a VIEWER")
        client.expect("GET", analysis_path, 200, headers=viewer)
        client.expect("POST", analysis_path, 403, headers=viewer)
        check("a VIEWER can read the analysis but not run one")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="http://127.0.0.1:3000", help="dashboard base URL")
    parser.add_argument(
        "--env-file", type=Path, required=True, help="environment file with the credentials"
    )
    args = parser.parse_args()
    env = read_env_file(args.env_file)
    missing = [
        name
        for name in (
            "INGESTION_API_KEY",
            "AUTH_BOOTSTRAP_ADMIN_USERNAME",
            "AUTH_BOOTSTRAP_ADMIN_PASSWORD",
        )
        if not env.get(name)
    ]
    if missing:
        print(f"Missing in {args.env_file}: {', '.join(missing)}", file=sys.stderr)
        return 2
    try:
        run(Client(args.url), env)
    except SmokeTestFailure as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError) as error:
        print(f"FAIL: cannot reach {args.url}: {error}", file=sys.stderr)
        return 1
    print("Smoke test passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
