from uuid import uuid4

import pytest
from factories import event, incident

pytestmark = pytest.mark.database


def analyzed(client, backend, **kwargs):
    target = incident([event(minutes=m) for m in (0, 4, 8)], **kwargs)
    backend.add(target)
    assert client.post(f"/api/v1/analysis/incidents/{target.id}").status_code == 201
    return target


def test_returns_the_latest_revision_per_requested_incident(client, backend):
    first = analyzed(client, backend)
    second = analyzed(client, backend)
    client.post(f"/api/v1/analysis/incidents/{first.id}")  # revision 2
    never_analyzed = uuid4()

    response = client.get(
        "/api/v1/analysis/incidents",
        params=[
            ("incidentId", str(first.id)),
            ("incidentId", str(second.id)),
            ("incidentId", str(never_analyzed)),
        ],
    )

    assert response.status_code == 200
    items = {item["incidentId"]: item for item in response.json()["items"]}
    assert set(items) == {str(first.id), str(second.id)}
    assert items[str(first.id)]["revision"] == 2
    assert items[str(second.id)]["revision"] == 1
    assert items[str(first.id)]["primaryCategory"] == "TIMEOUT"
    assert items[str(first.id)]["confidence"] == "HIGH"
    assert (items[str(first.id)]["service"], items[str(first.id)]["environment"]) == (
        "payment-service",
        "staging",
    )
    assert "candidates" not in items[str(first.id)]  # summary only


def test_without_ids_returns_the_most_recent_analyses(client, backend):
    older = analyzed(client, backend)
    newer = analyzed(client, backend)

    items = client.get("/api/v1/analysis/incidents?limit=2").json()["items"]

    assert [item["incidentId"] for item in items] == [str(newer.id), str(older.id)]


def test_unknown_incidents_give_an_empty_list(client):
    response = client.get(f"/api/v1/analysis/incidents?incidentId={uuid4()}")

    assert response.status_code == 200
    assert response.json() == {"items": []}


@pytest.mark.parametrize(
    "query",
    ["incidentId=not-a-uuid", "limit=0", "limit=101"],
)
def test_invalid_parameters_return_400(client, query):
    response = client.get(f"/api/v1/analysis/incidents?{query}")

    assert response.status_code == 400
    assert response.headers["content-type"] == "application/problem+json"


def test_too_many_ids_return_400(client):
    params = [("incidentId", str(uuid4())) for _ in range(101)]

    assert client.get("/api/v1/analysis/incidents", params=params).status_code == 400
