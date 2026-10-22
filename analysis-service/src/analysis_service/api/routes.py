"""Analysis API (`/api/v1/analysis`) and health endpoints."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Path, Query, Request, Response
from fastapi.responses import JSONResponse

from analysis_service import __version__
from analysis_service.logging_config import SERVICE_NAME
from analysis_service.models.analysis import (
    AnalysisHistory,
    IncidentAnalysis,
    LatestAnalyses,
    RelatedIncidents,
)
from analysis_service.services.analysis_service import AnalysisService

router = APIRouter(prefix="/api/v1/analysis", tags=["analysis"])
health_router = APIRouter(tags=["health"])

_ERRORS = {
    400: {"description": "Invalid incident ID or query parameter"},
    404: {"description": "Incident (or analysis) not found"},
    503: {"description": "incident-service, ingestion-service or the database is unavailable"},
}


def _service(request: Request) -> AnalysisService:
    return request.app.state.analysis_service


# Path parameter named like the JSON fields (camelCase), also in validation errors.
IncidentId = Annotated[UUID, Path(alias="incidentId", description="Incident ID (UUID)")]


@router.get(
    "/incidents",
    response_model=LatestAnalyses,
    responses={400: _ERRORS[400], 503: _ERRORS[503]},
    summary="Latest analysis per incident (summary only), most recent first",
)
async def latest_analyses(
    request: Request,
    incident_ids: Annotated[
        list[UUID] | None,
        Query(
            alias="incidentId",
            max_length=100,
            description="Only these incidents (repeat the parameter); default: all incidents",
        ),
    ] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> LatestAnalyses:
    return await _service(request).latest_per_incident(incident_ids, limit)


@router.post(
    "/incidents/{incidentId}",
    status_code=201,
    response_model=IncidentAnalysis,
    responses=_ERRORS,
    summary="Analyze an incident and store the result as a new revision",
)
async def analyze_incident(
    incident_id: IncidentId, request: Request, response: Response
) -> IncidentAnalysis:
    analysis = await _service(request).analyze(incident_id)
    response.headers["Location"] = f"/api/v1/analysis/incidents/{incident_id}"
    return analysis


@router.get(
    "/incidents/{incidentId}",
    response_model=IncidentAnalysis,
    responses=_ERRORS,
    summary="Latest stored analysis of an incident",
)
async def latest_analysis(incident_id: IncidentId, request: Request) -> IncidentAnalysis:
    return await _service(request).latest(incident_id)


@router.get(
    "/incidents/{incidentId}/history",
    response_model=AnalysisHistory,
    responses=_ERRORS,
    summary="Stored analyses of an incident, newest revision first",
)
async def analysis_history(
    incident_id: IncidentId,
    request: Request,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> AnalysisHistory:
    return await _service(request).history(incident_id, limit)


@router.get(
    "/incidents/{incidentId}/related",
    response_model=RelatedIncidents,
    responses=_ERRORS,
    summary="Incidents related to an incident (computed on request, not stored)",
)
async def related_incidents(incident_id: IncidentId, request: Request) -> RelatedIncidents:
    return await _service(request).related(incident_id)


@health_router.get("/health", summary="Liveness: the process is running")
def health() -> dict[str, str]:
    return {"status": "UP", "service": SERVICE_NAME, "version": __version__}


@health_router.get("/health/readiness", summary="Readiness: the database is reachable")
async def readiness(request: Request) -> JSONResponse:
    repository = getattr(request.app.state, "repository", None)
    try:
        if repository is None:
            raise RuntimeError("not started")
        await repository.ping()
        database = "UP"
    except Exception:
        database = "DOWN"
    status = "UP" if database == "UP" else "DOWN"
    return JSONResponse(
        {
            "status": status,
            "service": SERVICE_NAME,
            "version": __version__,
            "checks": {"database": database},
        },
        status_code=200 if status == "UP" else 503,
    )
