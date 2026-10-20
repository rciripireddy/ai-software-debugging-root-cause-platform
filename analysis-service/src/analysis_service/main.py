"""HTTP entry point for the analysis service.

Phase 1 exposes only a health endpoint. Incident analysis endpoints are added in a later phase.
"""

from fastapi import FastAPI

from analysis_service import __version__

SERVICE_NAME = "analysis-service"


def create_app() -> FastAPI:
    app = FastAPI(title=SERVICE_NAME, version=__version__)

    @app.get("/health", tags=["health"])
    def health() -> dict[str, str]:
        return {"status": "UP", "service": SERVICE_NAME, "version": __version__}

    return app


app = create_app()
