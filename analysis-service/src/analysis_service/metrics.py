"""Prometheus metrics, exported at ``GET /metrics`` (internal network only, see docs/operations.md).

Names and labels follow the Spring Boot services where the meaning is the same
(``http_server_requests_seconds`` with ``method``, ``uri``, ``status`` and ``outcome``), so one
query covers all three services. Labels only take values from small fixed sets: the URI is the
route template, never the requested path, and no label contains an incident or user ID.
"""

from collections.abc import Callable, Iterator
from typing import Any

from prometheus_client import (
    CONTENT_TYPE_LATEST,
    CollectorRegistry,
    Counter,
    Histogram,
    PlatformCollector,
    ProcessCollector,
    disable_created_metrics,
    generate_latest,
)
from prometheus_client.core import CounterMetricFamily, GaugeMetricFamily
from prometheus_client.registry import Collector

# No `*_created` series: they double the series count and nothing here uses them.
disable_created_metrics()

# The same boundaries as the Spring Boot services' http.server.requests histograms.
HTTP_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
ANALYSIS_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
ANALYSIS_RESULTS = ("success", "not_found", "failed")
BACKEND_SERVICES = ("incident-service", "ingestion-service")
UNMATCHED_URI = "UNKNOWN"

__all__ = ["CONTENT_TYPE_LATEST", "Metrics", "http_outcome"]


def http_outcome(status: int) -> str:
    """Spring Boot's outcome tag values."""
    if status < 200:
        return "INFORMATIONAL"
    if status < 300:
        return "SUCCESS"
    if status < 400:
        return "REDIRECTION"
    if status < 500:
        return "CLIENT_ERROR"
    return "SERVER_ERROR"


class _DatabasePoolCollector(Collector):
    """Reads the connection pool's statistics when Prometheus scrapes."""

    def __init__(self, stats: Callable[[], dict[str, Any] | None]) -> None:
        self._stats = stats

    def collect(self) -> Iterator[GaugeMetricFamily | CounterMetricFamily]:
        stats = self._stats()
        if stats is None:  # not started yet
            return
        size, available = stats.get("pool_size", 0), stats.get("pool_available", 0)
        connections = GaugeMetricFamily(
            "rcp_db_pool_connections",
            "Connections of the PostgreSQL pool, by state",
            labels=["state"],
        )
        connections.add_metric(["in_use"], size - available)
        connections.add_metric(["idle"], available)
        yield connections
        yield GaugeMetricFamily(
            "rcp_db_pool_requests_waiting",
            "Requests waiting for a free database connection",
            value=stats.get("requests_waiting", 0),
        )
        yield CounterMetricFamily(
            "rcp_db_pool_connection_errors",
            "Failed attempts to open a database connection",
            value=stats.get("connections_errors", 0),
        )


class Metrics:
    """The service's metrics, in a registry of their own (one per application instance)."""

    def __init__(self) -> None:
        self.registry = CollectorRegistry()
        # process_* (memory, CPU, open files; Linux only) and python_info.
        ProcessCollector(registry=self.registry)
        PlatformCollector(registry=self.registry)
        self.http_requests = Histogram(
            "http_server_requests_seconds",
            "HTTP requests, by route template and status",
            ["method", "uri", "status", "outcome"],
            buckets=HTTP_BUCKETS,
            registry=self.registry,
        )
        self.analyses = Counter(
            "rcp_analyses",
            "Analyses requested, by result: success, not_found or failed",
            ["result"],
            registry=self.registry,
        )
        self.analysis_duration = Histogram(
            "rcp_analysis_duration_seconds",
            "Time to run and store an analysis (successful and failed runs)",
            buckets=ANALYSIS_BUCKETS,
            registry=self.registry,
        )
        self.backend_failures = Counter(
            "rcp_backend_request_failures",
            "Calls to incident-service or ingestion-service that failed or were unusable",
            ["service"],
            registry=self.registry,
        )
        # Every series exists (at 0) before the first analysis or failure.
        for result in ANALYSIS_RESULTS:
            self.analyses.labels(result)
        for service in BACKEND_SERVICES:
            self.backend_failures.labels(service)

    def observe_request(self, method: str, uri: str | None, status: int, seconds: float) -> None:
        self.http_requests.labels(
            method, uri or UNMATCHED_URI, str(status), http_outcome(status)
        ).observe(seconds)

    def analysis_finished(self, result: str, seconds: float | None = None) -> None:
        self.analyses.labels(result).inc()
        if seconds is not None:
            self.analysis_duration.observe(seconds)

    def backend_failed(self, service: str) -> None:
        self.backend_failures.labels(service).inc()

    def watch_database_pool(self, stats: Callable[[], dict[str, Any] | None]) -> None:
        self.registry.register(_DatabasePoolCollector(stats))

    def exposition(self) -> bytes:
        return generate_latest(self.registry)
