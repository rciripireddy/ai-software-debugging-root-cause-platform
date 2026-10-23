"""Starts the service: ``python -m analysis_service``."""

import asyncio
import logging
import sys

import uvicorn

from analysis_service.config import ConfigurationError, get_settings, validate_settings
from analysis_service.logging_config import configure_logging

# Seconds uvicorn waits for in-flight requests after SIGTERM before closing them; below Docker's
# stop timeout, so the database pool is always closed cleanly.
GRACEFUL_SHUTDOWN_SECONDS = 10


def main() -> None:
    settings = get_settings()
    configure_logging(settings.log_format, settings.log_level, settings.app_environment)
    try:
        validate_settings(settings)
    except ConfigurationError as error:
        # One line per problem instead of a traceback; the values themselves are never logged.
        logging.getLogger("analysis_service").error(
            "Invalid configuration; the service does not start",
            extra={"action": "config.invalid", "problems": error.problems},
        )
        sys.exit(2)
    if sys.platform == "win32":
        # psycopg's async mode does not support the default Windows (Proactor) event loop.
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    uvicorn.run(
        "analysis_service.main:app",
        host=settings.server_host,
        port=settings.server_port,
        log_config=None,
        access_log=False,
        timeout_graceful_shutdown=GRACEFUL_SHUTDOWN_SECONDS,
    )


if __name__ == "__main__":
    main()
