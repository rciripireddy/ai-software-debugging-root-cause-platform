"""HTTP entry point for the analysis service."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from analysis_service import __version__
from analysis_service.analysis.engine import AnalysisSettings
from analysis_service.api.middleware import (
    CorrelationIdMiddleware,
    HttpMetricsMiddleware,
    SecurityHeadersMiddleware,
)
from analysis_service.api.problems import register_problem_handlers
from analysis_service.api.routes import health_router, metrics_router, router
from analysis_service.api.security import FixedWindowRateLimiter, TokenVerifier, require_secret
from analysis_service.clients.backend import BackendClient, create_http_client
from analysis_service.config import Settings, get_settings, validate_settings
from analysis_service.logging_config import SERVICE_NAME
from analysis_service.metrics import Metrics
from analysis_service.repositories.analysis_repository import AnalysisRepository
from analysis_service.repositories.database import open_database
from analysis_service.services.analysis_service import AnalysisService
from analysis_service.services.incident_loader import IncidentLoader


def create_app(
    settings: Settings | None = None,
    backend_transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    """Creates the application. ``backend_transport`` replaces the HTTP transport to the other
    services (used by tests)."""
    metrics = Metrics()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        config = settings or get_settings()
        validate_settings(config)
        jwt_secret = require_secret(config.auth_jwt_secret.get_secret_value(), "AUTH_JWT_SECRET")
        service_token = require_secret(
            config.internal_service_token.get_secret_value(), "INTERNAL_SERVICE_TOKEN"
        )
        app.state.token_verifier = TokenVerifier(
            jwt_secret, config.auth_issuer, config.auth_audience
        )
        app.state.auth_cookie_name = config.auth_cookie_name
        app.state.analysis_rate_limiter = FixedWindowRateLimiter(
            config.analysis_rate_limit_per_minute, 60
        )
        pool = await open_database(
            config.database_conninfo,
            min_size=config.db_pool_min_size,
            max_size=config.db_pool_max_size,
            timeout=config.db_connect_timeout.total_seconds(),
            attempts=config.db_startup_attempts,
        )
        app.state.db_pool = pool
        http = create_http_client(
            config.http_timeout.total_seconds(), service_token, backend_transport
        )
        backend = BackendClient(
            http,
            config.incident_service_url,
            config.ingestion_service_url,
            config.http_max_concurrency,
            metrics,
        )
        repository = AnalysisRepository(pool)
        app.state.repository = repository
        app.state.analysis_service = AnalysisService(
            IncidentLoader(
                backend, config.analysis_max_event_details, config.analysis_related_candidate_limit
            ),
            repository,
            AnalysisSettings(
                burst_window=config.analysis_burst_window,
                preceding_window=config.analysis_preceding_window,
                related_threshold=config.analysis_related_threshold,
            ),
            metrics,
        )
        try:
            yield
        finally:
            # Runs on SIGTERM after uvicorn has finished the in-flight requests.
            await http.aclose()
            await pool.close()
            app.state.db_pool = None

    app = FastAPI(
        title=SERVICE_NAME,
        version=__version__,
        description=(
            "Rule-based incident analysis: recurring patterns, related incidents and potential "
            "root causes with supporting evidence."
        ),
        lifespan=lifespan,
    )
    app.state.metrics = metrics
    app.state.db_pool = None
    metrics.watch_database_pool(
        lambda: app.state.db_pool.get_stats() if app.state.db_pool is not None else None
    )
    register_problem_handlers(app)
    app.add_middleware(SecurityHeadersMiddleware)
    # Added last, so it runs first: every request, including rejected ones, is measured.
    app.add_middleware(CorrelationIdMiddleware)
    app.add_middleware(HttpMetricsMiddleware)
    app.include_router(health_router)
    app.include_router(metrics_router)
    app.include_router(router)
    return app


app = create_app()
