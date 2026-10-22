"""Runs and stores incident analyses."""

import logging
import time
from collections.abc import Callable
from datetime import UTC, datetime
from uuid import UUID

from analysis_service.analysis import engine
from analysis_service.analysis.engine import AnalysisSettings
from analysis_service.models.analysis import (
    AnalysisHistory,
    IncidentAnalysis,
    LatestAnalyses,
    RelatedIncidents,
)
from analysis_service.repositories.analysis_repository import AnalysisRepository
from analysis_service.services.incident_loader import IncidentLoader, IncidentNotFoundError

log = logging.getLogger(__name__)


class AnalysisNotFoundError(Exception):
    def __init__(self, incident_id: UUID) -> None:
        super().__init__(f"No analysis exists for incident {incident_id}.")
        self.incident_id = incident_id


class AnalysisService:
    def __init__(
        self,
        loader: IncidentLoader,
        repository: AnalysisRepository,
        settings: AnalysisSettings,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._loader = loader
        self._repository = repository
        self._settings = settings
        self._clock = clock

    async def analyze(self, incident_id: UUID) -> IncidentAnalysis:
        started = time.perf_counter()
        log.info(
            "Incident analysis started",
            extra={"action": "analysis.start", "incidentId": str(incident_id)},
        )
        try:
            target = await self._loader.load_target(incident_id)
            pool = await self._loader.load_comparison_pool(target)
            report = engine.analyze(target, pool, self._settings, self._clock())
            stored = await self._repository.save(report)
        except IncidentNotFoundError:
            raise  # reported by the API as 404
        except Exception as exc:
            log.warning(
                "Incident analysis failed",
                extra={
                    "action": "analysis.failed",
                    "incidentId": str(incident_id),
                    "failureReason": f"{type(exc).__name__}: {exc}"[:300],
                    "durationMs": _elapsed_ms(started),
                },
            )
            raise

        primary = stored.primary_candidate
        log.info(
            "Incident analysis completed",
            extra={
                "action": "analysis.complete",
                "incidentId": str(incident_id),
                "analysisId": str(stored.id),
                "revision": stored.revision,
                "analysisVersion": stored.analysis_version,
                "eventsAnalyzed": stored.data_coverage.events_analyzed,
                "eventsWithStackTrace": stored.data_coverage.events_with_stack_trace,
                "incidentsCompared": stored.data_coverage.incidents_compared,
                "candidateCount": len(stored.candidates),
                "primaryCategory": primary.category.value if primary else None,
                "confidence": stored.confidence.value,
                "relatedIncidentCount": len(stored.related_incidents),
                "durationMs": _elapsed_ms(started),
            },
        )
        return stored

    async def latest(self, incident_id: UUID) -> IncidentAnalysis:
        analysis = await self._repository.latest(incident_id)
        if analysis is None:
            raise AnalysisNotFoundError(incident_id)
        return analysis

    async def history(self, incident_id: UUID, limit: int) -> AnalysisHistory:
        items = await self._repository.history(incident_id, limit)
        if not items:
            raise AnalysisNotFoundError(incident_id)
        return AnalysisHistory(incident_id=incident_id, items=items)

    async def latest_per_incident(
        self, incident_ids: list[UUID] | None, limit: int
    ) -> LatestAnalyses:
        return LatestAnalyses(items=await self._repository.latest_per_incident(incident_ids, limit))

    async def related(self, incident_id: UUID) -> RelatedIncidents:
        """Related incidents, computed now and not stored."""
        target = await self._loader.load_target(incident_id)
        pool = await self._loader.load_comparison_pool(target)
        items = engine.find_related(target, pool, self._settings)
        return RelatedIncidents(
            incident_id=incident_id,
            threshold=self._settings.related_threshold,
            incidents_compared=len(pool),
            items=items,
        )


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)
