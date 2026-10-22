"""HTTP clients for the incident and ingestion APIs.

The analysis service reads incidents and events only through these public APIs, never from the
other services' database schemas. Calls carry the current request's ``X-Correlation-Id`` so a
single ID connects the analysis request with the log lines of the services it reads from.
"""

import asyncio
import logging
from datetime import datetime
from typing import Any
from uuid import UUID

import httpx

from analysis_service.logging_config import correlation_id
from analysis_service.models.records import ApplicationRef, EventRecord, IncidentRecord

log = logging.getLogger(__name__)

INCIDENT_SERVICE = "incident-service"
INGESTION_SERVICE = "ingestion-service"
# Largest page size the incident API accepts.
MAX_PAGE_SIZE = 100


class BackendUnavailableError(Exception):
    """A required service could not be reached or returned an unusable response."""

    def __init__(self, service: str, reason: str) -> None:
        super().__init__(f"{service}: {reason}")
        self.service = service
        self.reason = reason


async def _add_correlation_id(request: httpx.Request) -> None:
    value = correlation_id.get()
    if value:
        request.headers["X-Correlation-Id"] = value


SERVICE_TOKEN_HEADER = "X-Service-Token"


def create_http_client(
    timeout_seconds: float,
    service_token: str,
    transport: httpx.AsyncBaseTransport | None = None,
) -> httpx.AsyncClient:
    """Calls are authenticated with the internal service token, never with user credentials."""
    return httpx.AsyncClient(
        timeout=timeout_seconds,
        transport=transport,
        headers={"Accept": "application/json", SERVICE_TOKEN_HEADER: service_token},
        event_hooks={"request": [_add_correlation_id]},
    )


class BackendClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        incident_service_url: str,
        ingestion_service_url: str,
        max_concurrency: int,
    ) -> None:
        self._http = http
        self._incident_url = incident_service_url.rstrip("/")
        self._ingestion_url = ingestion_service_url.rstrip("/")
        self._limit = asyncio.Semaphore(max_concurrency)

    async def get_incident(self, incident_id: UUID) -> IncidentRecord | None:
        body = await self._get(
            INCIDENT_SERVICE, f"{self._incident_url}/api/v1/incidents/{incident_id}"
        )
        return None if body is None else _parse(INCIDENT_SERVICE, _incident, body)

    async def list_incident_ids(self, application: str, limit: int) -> list[UUID]:
        """IDs of the application's most recently active incidents (all services/environments)."""
        if limit <= 0:
            return []
        body = await self._get(
            INCIDENT_SERVICE,
            f"{self._incident_url}/api/v1/incidents",
            params={"application": application, "size": min(limit, MAX_PAGE_SIZE), "page": 0},
        )
        if body is None:
            return []
        return _parse(INCIDENT_SERVICE, lambda b: [UUID(item["id"]) for item in b["items"]], body)

    async def get_event_details(self, event_id: UUID) -> tuple[str | None, str | None] | None:
        """Stack trace and source of a stored event, or None if the event does not exist."""
        body = await self._get(INGESTION_SERVICE, f"{self._ingestion_url}/api/v1/logs/{event_id}")
        if body is None:
            return None
        return _parse(INGESTION_SERVICE, lambda b: (b.get("stackTrace"), b.get("source")), body)

    async def _get(self, service: str, url: str, params: dict[str, Any] | None = None) -> Any:
        async with self._limit:
            try:
                response = await self._http.get(url, params=params)
            except httpx.HTTPError as exc:
                raise BackendUnavailableError(service, type(exc).__name__) from exc
        if response.status_code == 404:
            return None
        if response.status_code != 200:
            raise BackendUnavailableError(service, f"HTTP {response.status_code}")
        try:
            return response.json()
        except ValueError as exc:
            raise BackendUnavailableError(service, "response is not valid JSON") from exc


def _parse(service: str, parser, body: Any):
    try:
        return parser(body)
    except (KeyError, TypeError, ValueError) as exc:
        raise BackendUnavailableError(
            service, f"unexpected response ({type(exc).__name__})"
        ) from exc


def _timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _incident(body: dict[str, Any]) -> IncidentRecord:
    application = body["application"]
    return IncidentRecord(
        id=UUID(body["id"]),
        title=body["title"],
        status=body["status"],
        severity=body["severity"],
        application=ApplicationRef(
            id=UUID(application["id"]),
            name=application["name"],
            service=application["service"],
            environment=application["environment"],
        ),
        occurrence_count=int(body["occurrenceCount"]),
        first_seen_at=_timestamp(body["firstSeenAt"]),
        last_seen_at=_timestamp(body["lastSeenAt"]),
        fingerprint=body["fingerprint"],
        events=tuple(
            EventRecord(
                id=UUID(event["id"]),
                timestamp=_timestamp(event["timestamp"]),
                severity=event["severity"],
                message=event["message"],
                exception_type=event.get("exceptionType"),
                host=event.get("host"),
                trace_id=event.get("traceId"),
                correlation_id=event.get("correlationId"),
            )
            for event in body.get("recentEvents") or []
        ),
    )
