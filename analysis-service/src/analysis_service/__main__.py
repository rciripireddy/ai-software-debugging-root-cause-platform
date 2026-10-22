"""Starts the service: ``python -m analysis_service``."""

import asyncio
import sys

import uvicorn

from analysis_service.config import get_settings
from analysis_service.logging_config import configure_logging


def main() -> None:
    settings = get_settings()
    configure_logging(settings.log_format, settings.log_level)
    if sys.platform == "win32":
        # psycopg's async mode does not support the default Windows (Proactor) event loop.
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    uvicorn.run(
        "analysis_service.main:app",
        host=settings.server_host,
        port=settings.server_port,
        log_config=None,
        access_log=False,
    )


if __name__ == "__main__":
    main()
