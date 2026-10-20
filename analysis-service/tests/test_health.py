from fastapi.testclient import TestClient

from analysis_service import __version__
from analysis_service.main import SERVICE_NAME, create_app

client = TestClient(create_app())


def test_health_reports_up():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": SERVICE_NAME, "version": __version__}


def test_unknown_route_returns_404():
    assert client.get("/does-not-exist").status_code == 404


def test_openapi_schema_lists_health_endpoint():
    schema = client.get("/openapi.json").json()

    assert "/health" in schema["paths"]
