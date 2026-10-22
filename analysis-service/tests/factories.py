"""Builders for events and incidents used across the tests."""

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from analysis_service.models.records import ApplicationRef, EventRecord, IncidentRecord

BASE_TIME = datetime(2026, 10, 14, 10, 0, tzinfo=UTC)

PAYMENT_TIMEOUT_TRACE = (
    "java.net.SocketTimeoutException: Read timed out\n"
    "\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)\n"
    "\tat com.shop.payment.GatewayClient.authorize(GatewayClient.java:74)\n"
    "\tat com.shop.payment.PaymentService.charge(PaymentService.java:41)"
)


def event(
    *,
    minutes: float = 0,
    message: str = "Payment authorization failed",
    exception_type: str | None = "java.net.SocketTimeoutException",
    stack_trace: str | None = PAYMENT_TIMEOUT_TRACE,
    source: str | None = "com.shop.payment.GatewayClient",
    host: str | None = "payment-1",
    trace_id: str | None = None,
    severity: str = "ERROR",
    event_id: UUID | None = None,
) -> EventRecord:
    return EventRecord(
        id=event_id or uuid4(),
        timestamp=BASE_TIME + timedelta(minutes=minutes),
        severity=severity,
        message=message,
        exception_type=exception_type,
        host=host,
        trace_id=trace_id,
        stack_trace=stack_trace,
        source=source,
        details_loaded=True,
    )


def incident(
    events: list[EventRecord],
    *,
    service: str = "payment-service",
    environment: str = "staging",
    application: str = "demo-shop",
    fingerprint: str | None = None,
    status: str = "OPEN",
    incident_id: UUID | None = None,
    occurrence_count: int | None = None,
    title: str | None = None,
) -> IncidentRecord:
    ordered = tuple(sorted(events, key=lambda e: e.timestamp, reverse=True))
    return IncidentRecord(
        id=incident_id or uuid4(),
        title=title or f"{ordered[0].exception_type}: {ordered[0].message}",
        status=status,
        severity="ERROR",
        application=ApplicationRef(uuid4(), application, service, environment),
        occurrence_count=occurrence_count or len(ordered),
        first_seen_at=ordered[-1].timestamp,
        last_seen_at=ordered[0].timestamp,
        fingerprint=fingerprint or uuid4().hex * 2,
        events=ordered,
    )
