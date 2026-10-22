"""Authentication and authorization of API requests (see docs/security.md).

Access tokens are issued by incident-service (JWT, HS256) and verified here with the shared key.
The dashboard sends the token in the HttpOnly session cookie, API clients as
``Authorization: Bearer``. A state-changing request authenticated by the cookie must also send
the session's CSRF token in ``X-CSRF-Token``. This service accepts no service token: nothing
calls it service-to-service.
"""

import hmac
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Annotated, Literal

import jwt
from fastapi import Depends, Request

ROLES = frozenset({"ADMIN", "VIEWER"})
CSRF_HEADER = "X-CSRF-Token"
MIN_SECRET_BYTES = 32
# Tolerated clock difference between services.
CLOCK_SKEW_SECONDS = 30
_SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS"})

FailureReason = Literal["missing_credentials", "invalid", "expired"]


@dataclass(frozen=True)
class Caller:
    """A signed-in user, from a verified access token."""

    user_id: str
    username: str
    role: str
    csrf_token: str = field(repr=False)
    from_cookie: bool = False


class AuthenticationRequired(Exception):
    """No valid credential: missing, malformed, wrongly signed or expired. Answered with 401."""

    def __init__(self, reason: FailureReason) -> None:
        super().__init__(reason)
        self.reason = reason


class PermissionDenied(Exception):
    """Authenticated, but the role does not allow the request, or the CSRF token is missing."""

    def __init__(self, caller: Caller, reason: Literal["role", "csrf"]) -> None:
        super().__init__(reason)
        self.caller = caller
        self.reason = reason


class RateLimited(Exception):
    def __init__(self, retry_after_seconds: int, detail: str) -> None:
        super().__init__(detail)
        self.retry_after_seconds = retry_after_seconds
        self.detail = detail


def require_secret(value: str, variable: str) -> str:
    """Stops startup when a secret is missing or short, without showing its value."""
    if len(value.encode("utf-8")) < MIN_SECRET_BYTES:
        raise RuntimeError(
            f"{variable} must be set to a random value of at least {MIN_SECRET_BYTES} characters "
            "(see .env.example)"
        )
    return value


class TokenVerifier:
    """Checks signature (HS256 only), issuer, audience, expiry and the claims the platform uses."""

    def __init__(self, secret: str, issuer: str, audience: str) -> None:
        self._secret = secret
        self._issuer = issuer
        self._audience = audience

    def verify(self, token: str, from_cookie: bool) -> Caller:
        try:
            claims = jwt.decode(
                token,
                self._secret,
                algorithms=["HS256"],
                issuer=self._issuer,
                audience=self._audience,
                leeway=CLOCK_SKEW_SECONDS,
                options={"require": ["exp", "iat", "sub", "iss", "aud"]},
            )
        except jwt.ExpiredSignatureError as exc:
            raise AuthenticationRequired("expired") from exc
        except jwt.InvalidTokenError as exc:
            raise AuthenticationRequired("invalid") from exc
        values = [claims.get(name) for name in ("sub", "username", "role", "csrf")]
        if not all(isinstance(value, str) and value for value in values) or values[2] not in ROLES:
            raise AuthenticationRequired("invalid")
        subject, username, role, csrf = values
        return Caller(subject, username, role, csrf, from_cookie)


def authenticate(request: Request) -> Caller:
    """FastAPI dependency: the caller of the request, or AuthenticationRequired."""
    verifier: TokenVerifier = request.app.state.token_verifier
    authorization = request.headers.get("authorization")
    if authorization is not None:
        scheme, _, token = authorization.strip().partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise AuthenticationRequired("invalid")
        caller = verifier.verify(token.strip(), from_cookie=False)
    else:
        cookie = request.cookies.get(request.app.state.auth_cookie_name)
        if not cookie:
            raise AuthenticationRequired("missing_credentials")
        caller = verifier.verify(cookie, from_cookie=True)

    if caller.from_cookie and request.method not in _SAFE_METHODS:
        presented = request.headers.get(CSRF_HEADER, "")
        if not hmac.compare_digest(presented.encode(), caller.csrf_token.encode()):
            raise PermissionDenied(caller, "csrf")
    request.state.caller = caller
    return caller


def require_admin(caller: Annotated[Caller, Depends(authenticate)]) -> Caller:
    if caller.role != "ADMIN":
        raise PermissionDenied(caller, "role")
    return caller


class FixedWindowRateLimiter:
    """Counts events per key in fixed time windows. In memory, so each instance counts on its own
    and a restart resets the counts."""

    _CLEANUP_THRESHOLD = 10_000

    def __init__(
        self, limit: int, window_seconds: float, clock: Callable[[], float] = time.monotonic
    ) -> None:
        if limit < 1 or window_seconds <= 0:
            raise ValueError("limit must be positive and the window longer than zero")
        self._limit = limit
        self._window = window_seconds
        self._clock = clock
        self._windows: dict[str, tuple[float, int]] = {}
        self._lock = threading.Lock()

    def try_acquire(self, key: str) -> float:
        """Counts one event unless the key reached the limit; returns 0 when allowed, otherwise
        the seconds until it will be."""
        now = self._clock()
        with self._lock:
            start, count = self._windows.get(key, (now, 0))
            if now - start >= self._window:
                start, count = now, 0
            if count >= self._limit:
                return start + self._window - now
            self._windows[key] = (start, count + 1)
            if len(self._windows) > self._CLEANUP_THRESHOLD:
                self._windows = {
                    k: v for k, v in self._windows.items() if now - v[0] < self._window
                }
            return 0.0
