"""Request metrics, correlation ID handling, one log line per API request, and security
response headers."""

import logging
import re
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from analysis_service.api.problems import unexpected_error_response
from analysis_service.logging_config import correlation_id

log = logging.getLogger(__name__)

HEADER = "X-Correlation-Id"
_VALID_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}")


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """Takes the correlation ID from the request header when well-formed, otherwise generates
    one; returns it in the response header. Unexpected errors become a 500 problem response
    here, so they carry the correlation ID as well."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        value = request.headers.get(HEADER)
        if not value or not _VALID_ID.fullmatch(value):
            value = str(uuid.uuid4())
        token = correlation_id.set(value)
        started = time.perf_counter()
        try:
            try:
                response = await call_next(request)
            except Exception:
                log.error(
                    "Unexpected error",
                    exc_info=True,
                    extra={"action": "http.unexpected_error", "path": request.url.path},
                )
                response = unexpected_error_response(request)
            response.headers[HEADER] = value
            if request.url.path.startswith("/api/"):
                log.info(
                    "HTTP request completed",
                    extra={
                        "method": request.method,
                        "path": request.url.path,
                        "status": response.status_code,
                        "durationMs": int((time.perf_counter() - started) * 1000),
                    },
                )
            return response
        finally:
            correlation_id.reset(token)


class HttpMetricsMiddleware(BaseHTTPMiddleware):
    """Records every request in ``http_server_requests_seconds``. The ``uri`` label is the matched
    route template (e.g. ``/api/v1/analysis/incidents/{incidentId}``), never the requested path,
    so the number of series stays small."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        started = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            route = request.scope.get("route")
            request.app.state.metrics.observe_request(
                request.method,
                getattr(route, "path", None),
                status,
                time.perf_counter() - started,
            )


# The interactive API docs load Swagger UI / ReDoc from a CDN, so they keep the default policy.
_DOCS_PATHS = ("/docs", "/redoc")
_SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
}


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Adds the same security headers as the Spring Boot services. HSTS is left to the HTTPS
    proxy in front of the platform (see docs/security.md)."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        response = await call_next(request)
        response.headers.update(_SECURITY_HEADERS)
        if not request.url.path.startswith(_DOCS_PATHS):
            response.headers["Content-Security-Policy"] = (
                "default-src 'none'; frame-ancestors 'none'"
            )
            response.headers["Cache-Control"] = "no-store"
        return response
