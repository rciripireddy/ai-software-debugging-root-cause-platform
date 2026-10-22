"""Service configuration, read from environment variables (see docs/configuration.md)."""

from datetime import timedelta
from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    # PostgreSQL: the same variables as the Spring Boot services. The service only writes to
    # its own `analysis` schema.
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "rootcause"
    db_username: str = "rootcause"
    db_password: SecretStr = SecretStr("")
    db_pool_min_size: int = Field(default=1, ge=0)
    db_pool_max_size: int = Field(default=5, ge=1)
    db_connect_timeout: timedelta = timedelta(seconds=5)
    db_startup_attempts: int = Field(default=10, ge=1)

    # Incidents and events are read through the public REST APIs of the other services.
    incident_service_url: str = "http://localhost:8082"
    ingestion_service_url: str = "http://localhost:8081"
    http_timeout: timedelta = timedelta(seconds=5)
    http_max_concurrency: int = Field(default=8, ge=1)

    # Analysis limits and windows.
    analysis_max_event_details: int = Field(default=20, ge=1, le=50)
    analysis_related_candidate_limit: int = Field(default=50, ge=0, le=100)
    analysis_burst_window: timedelta = timedelta(minutes=15)
    analysis_preceding_window: timedelta = timedelta(minutes=2)
    analysis_related_threshold: int = Field(default=40, ge=1, le=100)
    # Manual analyses per user and minute (in memory, per instance).
    analysis_rate_limit_per_minute: int = Field(default=30, ge=1)

    # Authentication (see docs/security.md). Access tokens are issued by incident-service and
    # verified here with the shared key; the internal service token authenticates this service's
    # calls to the other services. No defaults: the service does not start without them.
    auth_jwt_secret: SecretStr = SecretStr("")
    auth_issuer: str = "root-cause-platform"
    auth_audience: str = "root-cause-platform-api"
    auth_cookie_name: str = "rcp_session"
    internal_service_token: SecretStr = SecretStr("")

    server_host: str = "0.0.0.0"
    server_port: int = 8000

    log_format: Literal["text", "json"] = "text"
    log_level: str = "INFO"

    @property
    def database_conninfo(self) -> str:
        from psycopg.conninfo import make_conninfo

        return make_conninfo(
            host=self.db_host,
            port=self.db_port,
            dbname=self.db_name,
            user=self.db_username,
            password=self.db_password.get_secret_value(),
            connect_timeout=max(1, int(self.db_connect_timeout.total_seconds())),
            application_name="analysis-service",
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
