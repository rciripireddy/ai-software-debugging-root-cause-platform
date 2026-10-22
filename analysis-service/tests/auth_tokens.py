"""Test-only credentials and access tokens, signed like incident-service signs them."""

import uuid
from datetime import UTC, datetime, timedelta

import jwt

JWT_SECRET = "test-only-jwt-secret-for-automated-tests-0001"
SERVICE_TOKEN = "test-only-internal-service-token-000000001"
ISSUER = "root-cause-platform"
AUDIENCE = "root-cause-platform-api"
CSRF = "test-csrf-token"


def make_token(
    role: str = "VIEWER",
    *,
    secret: str = JWT_SECRET,
    issued_at: datetime | None = None,
    lifetime: timedelta = timedelta(minutes=10),
    algorithm: str = "HS256",
    **claims: object,
) -> str:
    issued = issued_at or datetime.now(UTC)
    payload: dict[str, object] = {
        "iss": ISSUER,
        "aud": [AUDIENCE],
        "sub": str(uuid.uuid4()),
        "iat": issued,
        "exp": issued + lifetime,
        "jti": str(uuid.uuid4()),
        "username": f"{role.lower()}-user",
        "role": role,
        "csrf": CSRF,
    }
    payload.update(claims)
    return jwt.encode(payload, secret, algorithm=algorithm)


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
