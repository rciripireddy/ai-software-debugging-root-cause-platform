"""Correlation ID handling and one log line per API request."""

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
