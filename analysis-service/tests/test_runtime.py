"""Runtime behaviour without a database: probes, correlation IDs, metrics, configuration."""

import asyncio
import json
import logging
import re
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from analysis_service.analysis.engine import AnalysisSettings
from analysis_service.api.security import TokenVerifier
from analysis_service.clients.backend import (
    INCIDENT_SERVICE,
    BackendClient,
    BackendUnavailableError,
)
from analysis_service.config import Settings, configuration_problems
from analysis_service.logging_config import EcsJsonFormatter
from analysis_service.main import create_app
from analysis_service.metrics import Metrics, http_outcome
from analysis_service.services.analysis_service import AnalysisService
from analysis_service.services.incident_loader import IncidentNotFoundError

SECRET = "a-test-secret-that-is-long-enough-0000000001"


@pytest.fixture
def client() -> TestClient:
    # Without the lifespan (no database): the routes below do not need it.
    return TestClient(create_app())


def sample(metrics_text: str, name: str, **labels: str) -> float | None:
    for line in metrics_text.splitlines():
        if line.startswith("#"):
            continue
        match = re.fullmatch(r"([a-zA-Z_:][\w:]*)(?:\{(.*)\})? (\S+)", line)
        if not match or match[1] != name:
            continue
        found = dict(re.findall(r'(\w+)="([^"]*)"', match[2] or ""))
        if all(found.get(key) == value for key, value in labels.items()):
            return float(match[3])
    return None


# --- Probes -----------------------------------------------------------------------------------


def test_liveness_does_not_depend_on_the_database(client: TestClient) -> None:
    assert client.get("/health/liveness").json()["status"] == "UP"
    assert client.get("/health").status_code == 200


def test_readiness_fails_while_the_database_is_not_available(client: TestClient) -> None:
    response = client.get("/health/readiness")

    assert response.status_code == 503
    assert response.json()["checks"] == {"database": "DOWN"}


# --- Correlation IDs --------------------------------------------------------------------------


def test_a_valid_correlation_id_is_returned_unchanged(client: TestClient) -> None:
    response = client.get("/health", headers={"X-Correlation-Id": "req-2026.10:abc_1"})

    assert response.headers["X-Correlation-Id"] == "req-2026.10:abc_1"


@pytest.mark.parametrize(
    "unsafe",
    ["", "has space", "x" * 129, "line\tbreak", "<script>", "a;b", "ünïcode"],
)
def test_unsafe_or_oversized_correlation_ids_are_replaced(client: TestClient, unsafe: str) -> None:
    response = client.get("/health", headers={"X-Correlation-Id": unsafe.encode("utf-8")})

    replaced = response.headers["X-Correlation-Id"]
    assert replaced != unsafe
    assert re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", replaced)


# --- Metrics ----------------------------------------------------------------------------------


def test_metrics_endpoint_uses_route_templates_not_requested_paths(client: TestClient) -> None:
    # What the lifespan would set up, so the request is rejected as unauthenticated (401).
    client.app.state.token_verifier = TokenVerifier(SECRET, "issuer", "audience")
    client.app.state.auth_cookie_name = "rcp_session"
    incident_id = uuid4()
    client.get("/health")
    client.get(f"/api/v1/analysis/incidents/{incident_id}")  # 401: not signed in
    client.get("/no/such/path")

    response = client.get("/metrics")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    text = response.text
    assert str(incident_id) not in text and "/no/such/path" not in text
    count = "http_server_requests_seconds_count"
    assert sample(text, count, uri="/health", status="200", outcome="SUCCESS") == 1
    assert (
        sample(
            text,
            count,
            uri="/api/v1/analysis/incidents/{incidentId}",
            status="401",
            outcome="CLIENT_ERROR",
        )
        == 1
    )
    assert sample(text, count, uri="UNKNOWN", status="404") == 1


def test_counters_exist_before_the_first_analysis(client: TestClient) -> None:
    text = client.get("/metrics").text

    for result in ("success", "not_found", "failed"):
        assert sample(text, "rcp_analyses_total", result=result) == 0
    assert sample(text, "rcp_backend_request_failures_total", service="incident-service") == 0


def test_metrics_endpoint_is_not_in_the_public_api_documentation(client: TestClient) -> None:
    paths = client.get("/openapi.json").json()["paths"]

    assert "/metrics" not in paths
    assert "/health/liveness" in paths


def test_outcome_values_match_the_spring_services() -> None:
    assert [http_outcome(s) for s in (101, 200, 304, 404, 503)] == [
        "INFORMATIONAL",
        "SUCCESS",
        "REDIRECTION",
        "CLIENT_ERROR",
        "SERVER_ERROR",
    ]


def test_database_pool_statistics_are_read_at_scrape_time() -> None:
    metrics = Metrics()
    stats: dict[str, int] | None = None
    metrics.watch_database_pool(lambda: stats)
    assert "rcp_db_pool_connections" not in metrics.exposition().decode()

    stats = {"pool_size": 4, "pool_available": 1, "requests_waiting": 2, "connections_errors": 3}
    text = metrics.exposition().decode()

    assert sample(text, "rcp_db_pool_connections", state="in_use") == 3
    assert sample(text, "rcp_db_pool_connections", state="idle") == 1
    assert sample(text, "rcp_db_pool_requests_waiting") == 2
    assert sample(text, "rcp_db_pool_connection_errors_total") == 3


class _Loader:
    def __init__(self, error: Exception) -> None:
        self._error = error

    async def load_target(self, incident_id):
        raise self._error


def test_analyses_are_counted_by_result() -> None:
    metrics = Metrics()

    async def run(error: Exception) -> None:
        service = AnalysisService(
            _Loader(error), repository=None, settings=AnalysisSettings(), metrics=metrics
        )
        with pytest.raises(type(error)):
            await service.analyze(uuid4())

    asyncio.run(run(IncidentNotFoundError(uuid4())))
    asyncio.run(run(BackendUnavailableError(INCIDENT_SERVICE, "HTTP 503")))
    text = metrics.exposition().decode()

    assert sample(text, "rcp_analyses_total", result="not_found") == 1
    assert sample(text, "rcp_analyses_total", result="failed") == 1
    assert sample(text, "rcp_analyses_total", result="success") == 0
    assert sample(text, "rcp_analysis_duration_seconds_count") == 1


def test_unavailable_backends_are_counted_per_service() -> None:
    metrics = Metrics()
    transport = httpx.MockTransport(lambda request: httpx.Response(503))

    async def call() -> None:
        async with httpx.AsyncClient(transport=transport) as http:
            backend = BackendClient(http, "http://incident", "http://ingestion", 2, metrics)
            with pytest.raises(BackendUnavailableError):
                await backend.get_incident(uuid4())

    asyncio.run(call())

    text = metrics.exposition().decode()
    assert sample(text, "rcp_backend_request_failures_total", service="incident-service") == 1
    assert sample(text, "rcp_backend_request_failures_total", service="ingestion-service") == 0


# --- Configuration and logging ----------------------------------------------------------------


def valid_settings(**overrides) -> Settings:
    values = {
        "db_host": "postgres",
        "db_password": SecretStr("db-password"),
        "auth_jwt_secret": SecretStr(SECRET),
        "internal_service_token": SecretStr(SECRET),
    }
    return Settings(**{**values, **overrides})


def test_valid_settings_have_no_problems() -> None:
    assert configuration_problems(valid_settings()) == []


def test_every_problem_names_the_variable_without_showing_secret_values() -> None:
    short_secret = "short-secret-value"
    settings = valid_settings(
        db_host=" ",
        db_password=SecretStr(""),
        incident_service_url="incident-service:8082",
        auth_jwt_secret=SecretStr(short_secret),
        internal_service_token=SecretStr(""),
    )

    problems = configuration_problems(settings)
    text = "\n".join(problems)

    assert len(problems) == 5
    for variable in (
        "DB_HOST",
        "DB_PASSWORD",
        "INCIDENT_SERVICE_URL",
        "AUTH_JWT_SECRET",
        "INTERNAL_SERVICE_TOKEN",
    ):
        assert variable in text
    assert short_secret not in text


def test_startup_stops_with_invalid_configuration() -> None:
    app = create_app(valid_settings(db_password=SecretStr("")))

    with pytest.raises(RuntimeError, match="DB_PASSWORD must be set"), TestClient(app):
        pass


def test_json_logs_carry_the_environment() -> None:
    record = logging.LogRecord("x", logging.INFO, __file__, 1, "message", None, None)

    document = json.loads(EcsJsonFormatter(environment="staging").format(record))

    assert document["service.environment"] == "staging"
    assert document["service.name"] == "analysis-service"
