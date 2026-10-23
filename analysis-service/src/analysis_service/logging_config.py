"""Logging: plain text locally, one JSON object per line (ECS field names) in Docker.

Structured fields are passed with ``extra={...}`` and written as top-level keys, like the
key-value pairs of the Spring Boot services. The request correlation ID is added to every record.
"""

import json
import logging
import logging.config
from contextvars import ContextVar
from datetime import UTC, datetime

from analysis_service import __version__

SERVICE_NAME = "analysis-service"

correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)

# Attributes every LogRecord has; anything else was passed through `extra`. uvicorn adds
# `color_message`, a duplicate of the message with terminal color codes.
_STANDARD_ATTRIBUTES = frozenset(vars(logging.makeLogRecord({}))) | {
    "message",
    "asctime",
    "taskName",
    "color_message",
}


def _extra_fields(record: logging.LogRecord) -> dict[str, object]:
    return {
        key: value
        for key, value in vars(record).items()
        if key not in _STANDARD_ATTRIBUTES and not key.startswith("_")
    }


class CorrelationIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.correlationId = correlation_id.get()
        return True


class EcsJsonFormatter(logging.Formatter):
    def __init__(self, environment: str = "local") -> None:
        super().__init__()
        self._environment = environment

    def format(self, record: logging.LogRecord) -> str:
        document: dict[str, object] = {
            "@timestamp": datetime.fromtimestamp(record.created, UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            # WARN instead of WARNING, as in the Spring Boot services' logs.
            "log.level": "WARN" if record.levelname == "WARNING" else record.levelname,
            "process.thread.name": record.threadName,
            "service.name": SERVICE_NAME,
            "service.version": __version__,
            "service.environment": self._environment,
            "log.logger": record.name,
            "message": record.getMessage(),
            "ecs.version": "8.11",
        }
        document.update({k: v for k, v in _extra_fields(record).items() if v is not None})
        if record.exc_info and record.exc_info[0] is not None:
            document["error.type"] = record.exc_info[0].__name__
            document["error.message"] = str(record.exc_info[1])
            document["error.stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(document, default=str, ensure_ascii=False)


class KeyValueTextFormatter(logging.Formatter):
    def __init__(self) -> None:
        super().__init__("%(asctime)s %(levelname)-5s [%(name)s] %(message)s")

    def format(self, record: logging.LogRecord) -> str:
        text = super().format(record)
        fields = " ".join(
            f'{key}="{value}"' for key, value in _extra_fields(record).items() if value is not None
        )
        if fields:
            first_line, _, rest = text.partition("\n")
            text = f"{first_line} {fields}" + (f"\n{rest}" if rest else "")
        return text


def configure_logging(
    log_format: str = "text", level: str = "INFO", environment: str = "local"
) -> None:
    formatter = "json" if log_format == "json" else "text"
    logging.config.dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "filters": {"correlation": {"()": CorrelationIdFilter}},
            "formatters": {
                "json": {"()": EcsJsonFormatter, "environment": environment},
                "text": {"()": KeyValueTextFormatter},
            },
            "handlers": {
                "console": {
                    "class": "logging.StreamHandler",
                    "stream": "ext://sys.stdout",
                    "formatter": formatter,
                    "filters": ["correlation"],
                },
            },
            "root": {"handlers": ["console"], "level": level.upper()},
            "loggers": {
                # Requests are logged by the service itself (one line per API request).
                "uvicorn.access": {"handlers": [], "propagate": False},
                "uvicorn.error": {"level": "INFO"},
                "httpx": {"level": "WARNING"},
                "httpcore": {"level": "WARNING"},
                "psycopg.pool": {"level": "WARNING"},
            },
        }
    )
