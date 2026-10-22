"""Authentication, roles, CSRF, rate limiting and security headers of the analysis API."""

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from auth_tokens import CSRF, JWT_SECRET, SERVICE_TOKEN, bearer, make_token
from factories import event, incident
from fake_backend import FakeBackend
from fastapi.testclient import TestClient
from pydantic import SecretStr

from analysis_service.api.security import FixedWindowRateLimiter
from analysis_service.config import Settings
from analysis_service.main import create_app

pytestmark = pytest.mark.database

PROBLEM_JSON = "application/problem+json"


def _incident(backend: FakeBackend):
    target = incident([event(minutes=m) for m in (0, 4, 8)])
    backend.add(target)
    return target


def test_analysis_endpoints_require_a_signed_in_user(anonymous):
    incident_id = uuid4()
    for method, path in [
        ("GET", "/api/v1/analysis/incidents"),
        ("GET", f"/api/v1/analysis/incidents/{incident_id}"),
        ("GET", f"/api/v1/analysis/incidents/{incident_id}/history"),
        ("GET", f"/api/v1/analysis/incidents/{incident_id}/related"),
        ("POST", f"/api/v1/analysis/incidents/{incident_id}"),
    ]:
        response = anonymous.request(method, path)
        assert response.status_code == 401, path
        assert response.headers["content-type"] == PROBLEM_JSON
        assert response.headers["WWW-Authenticate"] == "Bearer"
        assert response.json()["title"] == "Authentication required"
        assert response.json()["correlationId"]


def test_viewers_read_but_only_admins_run_analyses(anonymous, backend):
    target = _incident(backend)
    viewer = bearer(make_token("VIEWER"))
    admin = bearer(make_token("ADMIN"))

    denied = anonymous.post(f"/api/v1/analysis/incidents/{target.id}", headers=viewer)
    assert denied.status_code == 403
    assert denied.json()["title"] == "Forbidden"
    assert not backend.requests, "nothing is read for a denied request"

    assert (
        anonymous.post(f"/api/v1/analysis/incidents/{target.id}", headers=admin).status_code == 201
    )
    assert (
        anonymous.get(f"/api/v1/analysis/incidents/{target.id}", headers=viewer).status_code == 200
    )
    assert anonymous.get("/api/v1/analysis/incidents", headers=viewer).status_code == 200


def test_cookie_sessions_need_the_csrf_token_to_run_an_analysis(anonymous, backend):
    target = _incident(backend)
    anonymous.cookies.set("rcp_session", make_token("ADMIN"))

    assert anonymous.get("/api/v1/analysis/incidents").status_code == 200
    missing = anonymous.post(f"/api/v1/analysis/incidents/{target.id}")
    assert missing.status_code == 403
    assert missing.json()["title"] == "CSRF token missing or invalid"
    wrong = anonymous.post(
        f"/api/v1/analysis/incidents/{target.id}", headers={"X-CSRF-Token": "wrong"}
    )
    assert wrong.status_code == 403

    accepted = anonymous.post(
        f"/api/v1/analysis/incidents/{target.id}", headers={"X-CSRF-Token": CSRF}
    )
    assert accepted.status_code == 201


def test_expired_tokens_are_reported_as_an_expired_session(anonymous):
    issued = datetime.now(UTC) - timedelta(hours=2)
    token = make_token("ADMIN", issued_at=issued, lifetime=timedelta(hours=1))

    response = anonymous.get("/api/v1/analysis/incidents", headers=bearer(token))

    assert response.status_code == 401
    assert response.json()["title"] == "Session expired"
    assert response.headers["WWW-Authenticate"] == 'Bearer error="invalid_token"'


# The HS512 token below is signed with the 45-byte test key, which PyJWT warns about.
@pytest.mark.filterwarnings("ignore::jwt.warnings.InsecureKeyLengthWarning")
def test_tampered_forged_and_foreign_tokens_are_rejected(anonymous):
    valid = make_token("VIEWER")
    header, payload, signature = valid.split(".")
    rejected = [
        f"{header}.{make_token('ADMIN').split('.')[1]}.{signature}",  # other claims, old signature
        make_token("ADMIN", secret="another-secret-that-is-also-long-enough-0"),
        make_token("ADMIN", iss="someone-else"),
        make_token("ADMIN", aud=["other-api"]),
        make_token("SUPERUSER"),
        make_token("ADMIN", csrf=""),
        make_token("ADMIN", issued_at=datetime.now(UTC) + timedelta(hours=1)),
        make_token("ADMIN", algorithm="HS512"),
        f"{header}.{payload}.",
        "not-a-token",
    ]
    for token in rejected:
        response = anonymous.get("/api/v1/analysis/incidents", headers=bearer(token))
        assert response.status_code == 401, token
        assert response.json()["title"] == "Authentication required"


def test_malformed_authorization_headers_are_rejected(anonymous):
    for value in ["Basic dGVzdDp0ZXN0", "Bearer", "Bearer   ", "Token abc"]:
        response = anonymous.get("/api/v1/analysis/incidents", headers={"Authorization": value})
        assert response.status_code == 401, value


def test_the_service_token_does_not_authenticate_users(anonymous):
    assert (
        anonymous.get(
            "/api/v1/analysis/incidents", headers={"X-Service-Token": SERVICE_TOKEN}
        ).status_code
        == 401
    )
    assert (
        anonymous.get("/api/v1/analysis/incidents", headers=bearer(SERVICE_TOKEN)).status_code
        == 401
    )


def test_calls_to_other_services_use_the_service_token_not_the_users_credentials(client, backend):
    target = _incident(backend)
    client.cookies.set("rcp_session", make_token("ADMIN"))

    assert client.post(f"/api/v1/analysis/incidents/{target.id}").status_code == 201

    assert backend.requests
    for request in backend.requests:
        assert request.headers["X-Service-Token"] == SERVICE_TOKEN
        assert "authorization" not in request.headers
        assert "cookie" not in request.headers


def test_health_endpoints_stay_public(anonymous):
    for path in ["/health", "/health/readiness"]:
        response = anonymous.get(path, headers=bearer("broken"))
        assert response.status_code == 200, path


def test_responses_carry_security_headers(client):
    response = client.get("/api/v1/analysis/incidents")

    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert response.headers["X-Frame-Options"] == "DENY"
    assert (
        response.headers["Content-Security-Policy"] == "default-src 'none'; frame-ancestors 'none'"
    )
    assert response.headers["Referrer-Policy"] == "no-referrer"
    assert response.headers["Cache-Control"] == "no-store"
    assert "Strict-Transport-Security" not in response.headers
    assert "Content-Security-Policy" not in client.get("/docs").headers


@pytest.fixture
def limited(settings: Settings, backend: FakeBackend) -> Iterator[TestClient]:
    app = create_app(
        settings.model_copy(update={"analysis_rate_limit_per_minute": 2}), backend.transport()
    )
    with TestClient(app) as test_client:
        yield test_client


def test_manual_analyses_are_rate_limited_per_user(limited, backend):
    target = _incident(backend)
    alice = bearer(make_token("ADMIN", sub="alice"))
    bob = bearer(make_token("ADMIN", sub="bob"))

    for _ in range(2):
        assert (
            limited.post(f"/api/v1/analysis/incidents/{target.id}", headers=alice).status_code
            == 201
        )
    response = limited.post(f"/api/v1/analysis/incidents/{target.id}", headers=alice)

    assert response.status_code == 429
    assert response.json()["title"] == "Too many requests"
    assert 1 <= int(response.headers["Retry-After"]) <= 60
    assert limited.post(f"/api/v1/analysis/incidents/{target.id}", headers=bob).status_code == 201
    assert limited.get(f"/api/v1/analysis/incidents/{target.id}", headers=alice).status_code == 200


def test_the_service_does_not_start_without_its_secrets(settings, backend):
    for update, variable in [
        ({"auth_jwt_secret": SecretStr("short-but-secret-value")}, "AUTH_JWT_SECRET"),
        ({"internal_service_token": SecretStr("")}, "INTERNAL_SERVICE_TOKEN"),
    ]:
        app = create_app(settings.model_copy(update=update), backend.transport())
        with pytest.raises(RuntimeError) as error:
            with TestClient(app):
                pass
        assert variable in str(error.value)
        assert "short-but-secret-value" not in str(error.value)
        assert JWT_SECRET not in str(error.value)


def test_rate_limiter_counts_per_key_in_fixed_windows():
    now = [1000.0]
    limiter = FixedWindowRateLimiter(2, 60, clock=lambda: now[0])

    assert limiter.try_acquire("a") == 0
    assert limiter.try_acquire("a") == 0
    now[0] += 20
    assert limiter.try_acquire("a") == pytest.approx(40)
    assert limiter.try_acquire("b") == 0
    now[0] += 40
    assert limiter.try_acquire("a") == 0
    with pytest.raises(ValueError):
        FixedWindowRateLimiter(0, 60)
