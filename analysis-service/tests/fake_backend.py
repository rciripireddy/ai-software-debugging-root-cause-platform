"""In-memory stand-in for the incident and ingestion APIs, used through httpx.MockTransport."""

import re
from urllib.parse import parse_qs

import httpx

from analysis_service.models.records import IncidentRecord

INCIDENT_URL = "http://incident-service.test"
INGESTION_URL = "http://ingestion-service.test"

_INCIDENT = re.compile(r"^/api/v1/incidents/(?P<id>[^/]+)$")
_LOG = re.compile(r"^/api/v1/logs/(?P<id>[^/]+)$")


def _iso(value) -> str:
    return value.isoformat().replace("+00:00", "Z")


def incident_json(incident: IncidentRecord) -> dict:
    app = incident.application
    return {
        "id": str(incident.id),
        "title": incident.title,
        "status": incident.status,
        "severity": incident.severity,
        "application": {
            "id": str(app.id),
            "name": app.name,
            "service": app.service,
            "environment": app.environment,
        },
        "occurrenceCount": incident.occurrence_count,
        "firstSeenAt": _iso(incident.first_seen_at),
        "lastSeenAt": _iso(incident.last_seen_at),
        "fingerprint": incident.fingerprint,
        "summary": None,
        "createdAt": _iso(incident.first_seen_at),
        "updatedAt": _iso(incident.last_seen_at),
        "recentEventLimit": 50,
        "recentEvents": [
            {
                "id": str(e.id),
                "timestamp": _iso(e.timestamp),
                "severity": e.severity,
                "message": e.message,
                "exceptionType": e.exception_type,
                "host": e.host,
                "traceId": e.trace_id,
                "correlationId": e.correlation_id,
            }
            for e in incident.events
        ],
    }


class FakeBackend:
    def __init__(self) -> None:
        self.incidents: dict[str, IncidentRecord] = {}
        self.requests: list[httpx.Request] = []
        self.unreachable: set[str] = set()  # hosts that refuse connections
        self.failing: set[str] = set()  # hosts that answer 500

    def add(self, *incidents: IncidentRecord) -> None:
        for incident in incidents:
            self.incidents[str(incident.id)] = incident

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    def _handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        host = f"{request.url.scheme}://{request.url.host}"
        if host in self.unreachable:
            raise httpx.ConnectError("Connection refused", request=request)
        if host in self.failing:
            return httpx.Response(500, json={"title": "Internal server error"})
        path = request.url.path
        if host == INCIDENT_URL:
            if path == "/api/v1/incidents":
                application = parse_qs(request.url.query.decode())["application"][0]
                items = [
                    incident_json(i)
                    for i in self.incidents.values()
                    if i.application.name == application
                ]
                items.sort(key=lambda item: item["lastSeenAt"], reverse=True)
                size = int(parse_qs(request.url.query.decode())["size"][0])
                return httpx.Response(
                    200,
                    json={
                        "items": items[:size],
                        "page": 0,
                        "size": size,
                        "totalItems": len(items),
                        "totalPages": 1,
                    },
                )
            match = _INCIDENT.match(path)
            if match and match["id"] in self.incidents:
                return httpx.Response(200, json=incident_json(self.incidents[match["id"]]))
        if host == INGESTION_URL:
            match = _LOG.match(path)
            if match:
                for incident in self.incidents.values():
                    for event in incident.events:
                        if str(event.id) == match["id"]:
                            return httpx.Response(
                                200,
                                json={
                                    "id": str(event.id),
                                    "stackTrace": event.stack_trace,
                                    "source": event.source,
                                    "message": event.message,
                                    "metadata": {"dataset": "test"},
                                },
                            )
        return httpx.Response(404, json={"title": "Resource not found"})
