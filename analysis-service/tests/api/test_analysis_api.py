import logging
from uuid import uuid4

import psycopg
import pytest
from factories import event, incident
from fake_backend import INCIDENT_URL

pytestmark = pytest.mark.database

PROBLEM_JSON = "application/problem+json"


def recurring_timeout(**kwargs):
    return incident([event(minutes=m, trace_id=f"trace-{m}") for m in (0, 4, 8)], **kwargs)


def test_analyze_incident_returns_and_stores_the_analysis(client, backend):
    target = recurring_timeout()
    other = incident([event(minutes=30)], environment="production", fingerprint=target.fingerprint)
    backend.add(target, other)

    response = client.post(f"/api/v1/analysis/incidents/{target.id}")

    assert response.status_code == 201
    assert response.headers["Location"] == f"/api/v1/analysis/incidents/{target.id}"
    body = response.json()
    assert body["incidentId"] == str(target.id)
    assert body["revision"] == 1
    assert body["analysisVersion"] == "1.0.0"
    assert body["confidence"] == "HIGH"
    primary = body["primaryCandidate"]
    assert primary == body["candidates"][0]
    assert primary["category"] == "TIMEOUT"
    assert primary["evidence"] and primary["confidenceBasis"] and primary["limitations"]
    assert body["relatedIncidents"][0]["incidentId"] == str(other.id)
    assert body["relatedIncidents"][0]["strength"] == "STRONG"
    assert body["patterns"]["recurrence"]["pattern"] == "BURST"
    assert body["dataCoverage"] == {
        "occurrenceCount": 3,
        "eventsAnalyzed": 3,
        "eventsWithDetails": 3,
        "eventsWithStackTrace": 3,
        "incidentsCompared": 1,
    }

    latest = client.get(f"/api/v1/analysis/incidents/{target.id}")
    assert latest.status_code == 200
    assert latest.json() == body


def test_repeated_analysis_adds_revisions_and_keeps_history(client, backend):
    target = recurring_timeout()
    backend.add(target)

    first = client.post(f"/api/v1/analysis/incidents/{target.id}").json()
    second = client.post(f"/api/v1/analysis/incidents/{target.id}").json()

    assert (first["revision"], second["revision"]) == (1, 2)
    assert first["id"] != second["id"]
    assert client.get(f"/api/v1/analysis/incidents/{target.id}").json()["revision"] == 2
    history = client.get(f"/api/v1/analysis/incidents/{target.id}/history").json()
    assert [item["revision"] for item in history["items"]] == [2, 1]
    assert history["items"][0]["primaryCategory"] == "TIMEOUT"
    assert (
        client.get(f"/api/v1/analysis/incidents/{target.id}/history?limit=1").json()["items"][0][
            "revision"
        ]
        == 2
    )


def test_stored_row_matches_the_response(client, backend, settings):
    target = recurring_timeout()
    backend.add(target)
    body = client.post(f"/api/v1/analysis/incidents/{target.id}").json()

    with psycopg.connect(settings.database_conninfo) as connection:
        row = connection.execute(
            """SELECT revision, analysis_version, confidence, primary_category, events_analyzed,
                      related_incident_count, result
               FROM analysis.incident_analyses WHERE id = %s""",
            (body["id"],),
        ).fetchone()

    revision, version, confidence, category, events, related, result = row
    assert (revision, version, confidence, category, events, related) == (
        1,
        "1.0.0",
        "HIGH",
        "TIMEOUT",
        3,
        0,
    )
    assert result["candidates"] == body["candidates"]
    assert "primaryCandidate" not in result  # derived from candidates, not stored twice


def test_missing_incident_returns_404_and_stores_nothing(client, backend, settings):
    missing = uuid4()

    response = client.post(f"/api/v1/analysis/incidents/{missing}")

    assert response.status_code == 404
    assert response.headers["content-type"] == PROBLEM_JSON
    body = response.json()
    assert body["title"] == "Resource not found"
    assert body["detail"] == f"Incident {missing} does not exist."
    assert body["correlationId"] == response.headers["X-Correlation-Id"]
    with psycopg.connect(settings.database_conninfo) as connection:
        count = connection.execute(
            "SELECT count(*) FROM analysis.incident_analyses WHERE incident_id = %s", (missing,)
        ).fetchone()[0]
    assert count == 0


def test_analysis_not_yet_created_returns_404_with_hint(client):
    incident_id = uuid4()

    response = client.get(f"/api/v1/analysis/incidents/{incident_id}")

    assert response.status_code == 404
    assert "Create one with POST" in response.json()["detail"]
    assert client.get(f"/api/v1/analysis/incidents/{incident_id}/history").status_code == 404


def test_incident_without_usable_evidence_gives_low_confidence(client, backend):
    target = incident(
        [
            event(
                message="Something odd happened",
                exception_type=None,
                stack_trace=None,
                source=None,
                severity="WARN",
            )
        ]
    )
    backend.add(target)

    response = client.post(f"/api/v1/analysis/incidents/{target.id}")

    assert response.status_code == 201
    body = response.json()
    assert body["confidence"] == "LOW"
    assert [c["category"] for c in body["candidates"]] == ["UNKNOWN"]
    assert "None of the analyzed events has a stack trace." in body["limitations"]


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("post", "/api/v1/analysis/incidents/not-a-uuid"),
        ("get", "/api/v1/analysis/incidents/not-a-uuid"),
        ("get", "/api/v1/analysis/incidents/00000000-0000-0000-0000-000000000001/history?limit=0"),
        ("get", "/api/v1/analysis/incidents/00000000-0000-0000-0000-000000000001/history?limit=x"),
    ],
)
def test_malformed_requests_return_400(client, method, path):
    response = getattr(client, method)(path)

    assert response.status_code == 400
    assert response.headers["content-type"] == PROBLEM_JSON
    body = response.json()
    assert body["title"] == "Validation failed"
    assert body["errors"][0]["field"] in ("incidentId", "limit")


def test_unsupported_method_returns_problem(client):
    response = client.delete(f"/api/v1/analysis/incidents/{uuid4()}")

    assert response.status_code == 405
    assert response.json()["title"] == "Method Not Allowed"


def test_unreachable_incident_service_returns_503(client, backend, caplog):
    backend.unreachable.add(INCIDENT_URL)
    incident_id = uuid4()

    with caplog.at_level(logging.INFO):
        response = client.post(f"/api/v1/analysis/incidents/{incident_id}")

    assert response.status_code == 503
    assert response.json()["detail"] == "incident-service is temporarily unavailable. Retry later."
    failed = [r for r in caplog.records if getattr(r, "action", None) == "analysis.failed"]
    assert failed and failed[0].incidentId == str(incident_id)
    assert "Traceback" not in caplog.text


def test_failing_ingestion_service_returns_503(client, backend):
    target = recurring_timeout()
    backend.add(target)
    backend.failing.add("http://ingestion-service.test")

    response = client.post(f"/api/v1/analysis/incidents/{target.id}")

    assert response.status_code == 503
    assert response.json()["detail"].startswith("ingestion-service")


def test_database_errors_return_503(client, backend, monkeypatch):
    target = recurring_timeout()
    backend.add(target)

    async def broken(*args, **kwargs):
        raise psycopg.OperationalError("connection is bad")

    monkeypatch.setattr(client.app.state.repository, "save", broken)

    response = client.post(f"/api/v1/analysis/incidents/{target.id}")

    assert response.status_code == 503
    assert response.json()["detail"] == "The database is temporarily unavailable. Retry later."


def test_unexpected_errors_return_500_without_details(client, backend, monkeypatch):
    target = recurring_timeout()
    backend.add(target)

    async def broken(*args, **kwargs):
        raise RuntimeError("secret internal detail")

    monkeypatch.setattr(client.app.state.repository, "save", broken)

    response = client.post(f"/api/v1/analysis/incidents/{target.id}")

    assert response.status_code == 500
    assert "secret" not in response.text
    assert response.json()["correlationId"] == response.headers["X-Correlation-Id"]


def test_related_endpoint_computes_without_storing(client, backend):
    target = recurring_timeout()
    same_error = incident(
        [event(minutes=2)], environment="production", fingerprint=target.fingerprint
    )
    unrelated = incident(
        [
            event(
                message="Export failed",
                exception_type="com.shop.ExportException",
                stack_trace=None,
                source=None,
            )
        ],
        service="export-service",
    )
    backend.add(target, same_error, unrelated)

    response = client.get(f"/api/v1/analysis/incidents/{target.id}/related")

    assert response.status_code == 200
    body = response.json()
    assert body["threshold"] == 40
    assert body["incidentsCompared"] == 2
    assert [item["incidentId"] for item in body["items"]] == [str(same_error.id)]
    assert client.get(f"/api/v1/analysis/incidents/{target.id}").status_code == 404


def test_correlation_id_is_passed_to_the_backend_services(client, backend):
    target = recurring_timeout()
    backend.add(target)

    response = client.post(
        f"/api/v1/analysis/incidents/{target.id}", headers={"X-Correlation-Id": "analysis-test-42"}
    )

    assert response.headers["X-Correlation-Id"] == "analysis-test-42"
    assert backend.requests
    assert {r.headers.get("X-Correlation-Id") for r in backend.requests} == {"analysis-test-42"}


def test_completed_analysis_is_logged_with_its_outcome(client, backend, caplog):
    target = recurring_timeout()
    backend.add(target)

    with caplog.at_level(logging.INFO):
        client.post(f"/api/v1/analysis/incidents/{target.id}")

    started = next(r for r in caplog.records if getattr(r, "action", None) == "analysis.start")
    completed = next(r for r in caplog.records if getattr(r, "action", None) == "analysis.complete")
    assert started.incidentId == str(target.id)
    assert (
        completed.eventsAnalyzed,
        completed.candidateCount,
        completed.primaryCategory,
        completed.relatedIncidentCount,
        completed.confidence,
    ) == (3, 1, "TIMEOUT", 0, "HIGH")
    assert completed.revision == 1
    assert "Payment authorization failed" not in caplog.text  # event messages are not logged


def test_health_and_readiness(client):
    assert client.get("/health").json()["status"] == "UP"
    readiness = client.get("/health/readiness")
    assert readiness.status_code == 200
    assert readiness.json()["checks"] == {"database": "UP"}
