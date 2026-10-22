"""RFC 9457 problem details, in the same shape as the Spring Boot services' error responses.

Responses never contain stack traces or internal exception details.
"""

import logging
from http import HTTPStatus

import psycopg
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from psycopg_pool import PoolTimeout
from starlette.exceptions import HTTPException as StarletteHTTPException

from analysis_service.clients.backend import BackendUnavailableError
from analysis_service.logging_config import correlation_id
from analysis_service.services.analysis_service import AnalysisNotFoundError
from analysis_service.services.incident_loader import IncidentNotFoundError

log = logging.getLogger(__name__)

PROBLEM_JSON = "application/problem+json"


def problem_response(
    request: Request, status: int, title: str, detail: str, **extra: object
) -> JSONResponse:
    body: dict[str, object] = {
        "title": title,
        "status": status,
        "detail": detail,
        "instance": request.url.path,
        "correlationId": correlation_id.get(),
    }
    body.update(extra)
    if status < 500:
        log.info(
            "Request rejected",
            extra={"action": "http.rejected", "status": status, "title": title, "detail": detail},
        )
    return JSONResponse(body, status_code=status, media_type=PROBLEM_JSON)


def unexpected_error_response(request: Request) -> JSONResponse:
    return problem_response(
        request,
        500,
        "Internal server error",
        "An unexpected error occurred. Quote the correlation ID when reporting this problem.",
    )


def register_problem_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def validation_failed(request: Request, exc: RequestValidationError) -> JSONResponse:
        errors = [
            {
                "field": ".".join(str(part) for part in error["loc"][1:]) or str(error["loc"][0]),
                "message": error["msg"],
            }
            for error in exc.errors()
        ]
        return problem_response(
            request,
            400,
            "Validation failed",
            "The request contains invalid parameters.",
            errors=errors,
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        title = HTTPStatus(exc.status_code).phrase
        detail = exc.detail if isinstance(exc.detail, str) and exc.detail != title else title
        response = problem_response(request, exc.status_code, title, detail)
        if exc.headers:
            response.headers.update(exc.headers)
        return response

    @app.exception_handler(IncidentNotFoundError)
    async def incident_not_found(request: Request, exc: IncidentNotFoundError) -> JSONResponse:
        return problem_response(request, 404, "Resource not found", str(exc))

    @app.exception_handler(AnalysisNotFoundError)
    async def analysis_not_found(request: Request, exc: AnalysisNotFoundError) -> JSONResponse:
        return problem_response(
            request,
            404,
            "Resource not found",
            f"{exc} Create one with POST /api/v1/analysis/incidents/{exc.incident_id}.",
        )

    @app.exception_handler(BackendUnavailableError)
    async def backend_unavailable(request: Request, exc: BackendUnavailableError) -> JSONResponse:
        log.warning(
            "Backend service unavailable",
            extra={
                "action": "backend.unavailable",
                "backendService": exc.service,
                "failureReason": exc.reason,
            },
        )
        return problem_response(
            request,
            503,
            "Service unavailable",
            f"{exc.service} is temporarily unavailable. Retry later.",
        )

    async def database_unavailable(request: Request, exc: Exception) -> JSONResponse:
        log.warning(
            "Database unavailable",
            extra={"action": "db.unavailable", "failureReason": type(exc).__name__},
        )
        return problem_response(
            request,
            503,
            "Service unavailable",
            "The database is temporarily unavailable. Retry later.",
        )

    app.add_exception_handler(psycopg.OperationalError, database_unavailable)
    app.add_exception_handler(PoolTimeout, database_unavailable)
