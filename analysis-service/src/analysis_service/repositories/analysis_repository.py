"""Storage of analysis results in `analysis.incident_analyses`."""

from uuid import UUID

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from analysis_service.models.analysis import (
    AnalysisHistoryItem,
    AnalysisReport,
    IncidentAnalysis,
)


class AnalysisRepository:
    def __init__(self, pool: AsyncConnectionPool) -> None:
        self._pool = pool

    async def save(self, report: AnalysisReport) -> IncidentAnalysis:
        """Stores the report as the incident's next revision."""
        primary = report.primary_candidate
        result = report.model_dump(mode="json", by_alias=True, exclude={"primary_candidate"})
        async with self._pool.connection() as connection, connection.transaction():
            # Serializes concurrent analyses of the same incident so revisions stay sequential.
            await connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (str(report.incident_id),)
            )
            cursor = await connection.execute(
                """INSERT INTO analysis.incident_analyses (
                       incident_id, revision, analysis_version, generated_at, summary, confidence,
                       primary_category, primary_title, events_analyzed, related_incident_count,
                       result)
                   SELECT %(incident_id)s, COALESCE(MAX(revision), 0) + 1, %(version)s,
                          %(generated_at)s, %(summary)s, %(confidence)s, %(category)s, %(title)s,
                          %(events)s, %(related)s, %(result)s
                   FROM analysis.incident_analyses
                   WHERE incident_id = %(incident_id)s
                   RETURNING id, revision""",
                {
                    "incident_id": report.incident_id,
                    "version": report.analysis_version,
                    "generated_at": report.generated_at,
                    "summary": report.summary,
                    "confidence": report.confidence.value,
                    "category": primary.category.value if primary else None,
                    "title": primary.title[:500] if primary else None,
                    "events": report.data_coverage.events_analyzed,
                    "related": len(report.related_incidents),
                    "result": Jsonb(result),
                },
            )
            analysis_id, revision = await cursor.fetchone()
        return IncidentAnalysis.model_validate({**result, "id": analysis_id, "revision": revision})

    async def latest(self, incident_id: UUID) -> IncidentAnalysis | None:
        async with self._pool.connection() as connection:
            cursor = await connection.execute(
                """SELECT id, revision, result FROM analysis.incident_analyses
                   WHERE incident_id = %s ORDER BY revision DESC LIMIT 1""",
                (incident_id,),
            )
            row = await cursor.fetchone()
        if row is None:
            return None
        analysis_id, revision, result = row
        return IncidentAnalysis.model_validate({**result, "id": analysis_id, "revision": revision})

    async def history(self, incident_id: UUID, limit: int) -> list[AnalysisHistoryItem]:
        async with self._pool.connection() as connection:
            cursor = connection.cursor(row_factory=dict_row)
            await cursor.execute(
                """SELECT id, revision, analysis_version, generated_at, confidence,
                          primary_category, primary_title, summary
                   FROM analysis.incident_analyses
                   WHERE incident_id = %s ORDER BY revision DESC LIMIT %s""",
                (incident_id, limit),
            )
            rows = await cursor.fetchall()
        return [AnalysisHistoryItem.model_validate(row) for row in rows]

    async def ping(self) -> None:
        async with self._pool.connection(timeout=2) as connection:
            await connection.execute("SELECT 1")
