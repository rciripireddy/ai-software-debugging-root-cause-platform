"""HTTP entry point for the analysis service."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from analysis_service import __version__
from analysis_service.analysis.engine import AnalysisSettings
from analysis_service.api.middleware import CorrelationIdMiddleware
from analysis_service.api.problems import register_problem_handlers
from analysis_service.api.routes import health_router, router
from analysis_service.clients.backend import BackendClient, create_http_client
from analysis_service.config import Settings, get_settings
from analysis_service.logging_config import SERVICE_NAME
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

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        config = settings or get_settings()
        pool = await open_database(
            config.database_conninfo,
            min_size=config.db_pool_min_size,
            max_size=config.db_pool_max_size,
            timeout=config.db_connect_timeout.total_seconds(),
            attempts=config.db_startup_attempts,
        )
        http = create_http_client(config.http_timeout.total_seconds(), backend_transport)
        backend = BackendClient(
            http,
            config.incident_service_url,
            config.ingestion_service_url,
            config.http_max_concurrency,
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
        )
        try:
            yield
        finally:
            await http.aclose()
            await pool.close()

    app = FastAPI(
        title=SERVICE_NAME,
        version=__version__,
        description=(
            "Rule-based incident analysis: recurring patterns, related incidents and potential "
            "root causes with supporting evidence."
        ),
        lifespan=lifespan,
    )
    register_problem_handlers(app)
    app.add_middleware(CorrelationIdMiddleware)
    app.include_router(health_router)
    app.include_router(router)
    return app


app = create_app()
