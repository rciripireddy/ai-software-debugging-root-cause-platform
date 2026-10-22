from collections.abc import Iterator

import pytest
from fake_backend import INCIDENT_URL, INGESTION_URL, FakeBackend
from fastapi.testclient import TestClient
from testcontainers.community.postgres import PostgresContainer

from analysis_service.config import Settings
from analysis_service.main import create_app


@pytest.fixture(scope="session")
def postgres() -> Iterator[PostgresContainer]:
    with PostgresContainer("postgres:17-alpine", driver=None) as container:
        yield container


@pytest.fixture(scope="session")
def settings(postgres: PostgresContainer) -> Settings:
    return Settings(
        db_host=postgres.get_container_host_ip(),
        db_port=int(postgres.get_exposed_port(5432)),
        db_name=postgres.dbname,
        db_username=postgres.username,
        db_password=postgres.password,
        incident_service_url=INCIDENT_URL,
        ingestion_service_url=INGESTION_URL,
    )


@pytest.fixture
def backend() -> FakeBackend:
    return FakeBackend()


@pytest.fixture
def client(settings: Settings, backend: FakeBackend) -> Iterator[TestClient]:
    with TestClient(create_app(settings, backend.transport())) as test_client:
        yield test_client
