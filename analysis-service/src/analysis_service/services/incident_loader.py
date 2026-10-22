"""Loads an incident and the incidents it is compared with, through the backend APIs."""

import asyncio
from uuid import UUID

from analysis_service.clients.backend import BackendClient
from analysis_service.models.records import EventRecord, IncidentRecord


class IncidentNotFoundError(Exception):
    def __init__(self, incident_id: UUID) -> None:
        super().__init__(f"Incident {incident_id} does not exist.")
        self.incident_id = incident_id


class IncidentLoader:
    def __init__(
        self, backend: BackendClient, max_event_details: int, candidate_limit: int
    ) -> None:
        self._backend = backend
        self._max_event_details = max_event_details
        self._candidate_limit = candidate_limit

    async def load_target(self, incident_id: UUID) -> IncidentRecord:
        """The incident with its recent events; stack trace and source for the newest of them."""
        incident = await self._backend.get_incident(incident_id)
        if incident is None:
            raise IncidentNotFoundError(incident_id)
        return await self._with_details(incident, self._max_event_details)

    async def load_comparison_pool(self, target: IncidentRecord) -> list[IncidentRecord]:
        """Recently active incidents of the same application (any service, environment, status).

        Events of one incident share a fingerprint, so the details of its newest event represent
        the incident; trace IDs and timestamps of all recent events come with the incident.
        """
        ids = await self._backend.list_incident_ids(
            target.application.name, self._candidate_limit + 1
        )
        ids = [incident_id for incident_id in ids if incident_id != target.id][
            : self._candidate_limit
        ]
        incidents = await asyncio.gather(*(self._backend.get_incident(i) for i in ids))
        found = [incident for incident in incidents if incident is not None]
        return list(await asyncio.gather(*(self._with_details(i, 1) for i in found)))

    async def _with_details(self, incident: IncidentRecord, count: int) -> IncidentRecord:
        newest = incident.events[:count]
        details = await asyncio.gather(*(self._backend.get_event_details(e.id) for e in newest))
        by_id: dict[UUID, EventRecord] = {}
        for event, detail in zip(newest, details, strict=True):
            if detail is not None:
                by_id[event.id] = event.with_details(*detail)
        return incident.with_events(tuple(by_id.get(e.id, e) for e in incident.events))
