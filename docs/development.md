# Development

Working on the platform beyond `docker compose up` (see the [README](../README.md#getting-started)):
sample data, querying the APIs, and running each part outside Docker. The commands that CI runs,
and how to run them locally, are in [ci.md](ci.md).

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Docker with Compose v2 | Compose 2.20+ | The stack, and the backend tests (Testcontainers) |
| Java (JDK) | 21 | The Spring Boot services outside Docker |
| Python | 3.12+ | The analysis service outside Docker; the sample-data scripts |
| Node.js | 24 (22.22+ works) | The dashboard outside Docker |

Maven does not need to be installed: each Java service includes the Maven Wrapper (`mvnw`).

## Sample data

With the stack running, send the synthetic events in `sample-data/application-logs.jsonl`, wait
for the incidents created from them and analyze them:

```bash
python sample-data/send_sample_logs.py --application demo-shop-trial --analyze
```

The script sends the events with `INGESTION_API_KEY` and signs in to read and analyze the
incidents. Both are taken from environment variables or, for local development, from `.env` (the
bootstrap admin unless `RCP_USERNAME`/`RCP_PASSWORD` are set); they are never printed. The ten
events contain three occurrences of the same payment timeout, two occurrences of a checkout
failure, two other errors and three `INFO`/`WARN` events. Example output:

```text
Incidents for 'demo-shop-trial' (created automatically via Kafka):
    2 x  checkout-service   OPEN    com.shop.checkout.PaymentFailedException: Order could not be completed
    3 x  payment-service    OPEN    java.net.SocketTimeoutException: Payment authorization failed
    1 x  catalog-service    OPEN    java.lang.NullPointerException: Failed to render product page
    1 x  inventory-service  OPEN    org.springframework.dao.DataAccessResourceFailureException: Unable to ...

Analysis (rule-based; potential causes, not confirmed root causes):
  checkout-service   DOWNSTREAM_DEPENDENCY  HIGH    Failure propagated from payment-service (SocketTimeoutException)
  payment-service    TIMEOUT                HIGH    Timeout waiting for a downstream dependency called from GatewayClient.authorize
  catalog-service    APPLICATION_CODE       MEDIUM  NullPointerException in application code at ProductMapper.toView (ProductMapper.
  inventory-service  DATABASE               MEDIUM  Database connectivity or connection-pool problem (accessed from StockRepository.
```

Single-occurrence incidents are capped at `MEDIUM`. Running the script again with the same
`--application` adds new occurrences to the same open incidents. For a larger set (16 labelled
scenarios across 13 services), run `python -m evaluation --live --application demo-store` in
`analysis-service/` (same credentials). More: [sample-data/README.md](../sample-data/README.md).

## Querying the APIs

Every endpoint except sign-in and health needs credentials. Sign in first and use the token
([api.md](api.md#signing-in-with-curl)):

```bash
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8082/api/v1/incidents?application=demo-shop-trial&status=OPEN'
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8082/api/v1/incidents/<incident-id>'
curl -H "Authorization: Bearer $TOKEN" -X POST 'http://localhost:8000/api/v1/analysis/incidents/<incident-id>'   # ADMIN only
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8000/api/v1/analysis/incidents/<incident-id>/history'
```

| URL | What |
| --- | --- |
| <http://localhost:8081/api/v1/logs> | Log ingestion API (`POST` with `X-Api-Key`) |
| <http://localhost:8082/api/v1/auth/login> | Sign-in API (`POST`) |
| <http://localhost:8082/api/v1/incidents> | Incident API |
| <http://localhost:8000/api/v1/analysis/incidents/{id}> | Analysis API (`POST` to analyze, `GET` for the latest result) |
| <http://localhost:8000/docs> | Analysis service OpenAPI docs |

These service ports are development ports (`docker-compose.override.yml`, bound to `127.0.0.1`).
The same APIs are reachable through the dashboard under `/api/ingestion`, `/api/incident` and
`/api/analysis`. Endpoints, fields and error codes: [api.md](api.md); health checks:
[operations.md](operations.md#health-model); inspecting Kafka topics and the outbox:
[events.md](events.md#inspecting-topics-locally).

If a host port is already in use (for example, a local PostgreSQL on 5432), change the matching
`*_PORT` variable in `.env`, e.g. `POSTGRES_PORT=5433`.

## Running the services outside Docker

Use separate terminals.

**Spring Boot services.** They need PostgreSQL and Kafka; start both with Docker Compose and point
the services at them. The defaults are `localhost:5432` (database and user `rootcause`) and Kafka
at `localhost:9092`, the broker's host listener:

```bash
docker compose up -d postgres kafka
# Use the values from .env. PowerShell: $env:DB_PORT='5432'; $env:DB_PASSWORD='...'
export DB_PORT='<POSTGRES_PORT>' DB_PASSWORD='<POSTGRES_PASSWORD>'
# Both services also need the auth secrets (see configuration.md#authentication):
export AUTH_JWT_SECRET='<from .env>' INTERNAL_SERVICE_TOKEN='<from .env>'
export INGESTION_API_KEY='<from .env>'                 # ingestion-service
export AUTH_BOOTSTRAP_ADMIN_USERNAME=admin AUTH_BOOTSTRAP_ADMIN_PASSWORD='<from .env>'   # incident-service

cd backend/ingestion-service      # start this one first: incident tables reference its schema
./mvnw spring-boot:run            # Windows: .\mvnw.cmd spring-boot:run

cd backend/incident-service
./mvnw spring-boot:run
```

**Analysis service.**

```bash
cd analysis-service
python -m venv .venv
source .venv/bin/activate         # Windows: .venv\Scripts\activate
pip install -r requirements.lock -r requirements-dev.lock   # exact versions, as in CI and Docker
pip install --no-deps -e .
python -m evaluation              # evaluation on the labelled synthetic scenarios (no services needed)
# Run the service (needs PostgreSQL; analysis requests also need the other two services):
# export DB_PORT='<POSTGRES_PORT>' DB_PASSWORD='<POSTGRES_PASSWORD>' \
#   AUTH_JWT_SECRET='<from .env>' INTERNAL_SERVICE_TOKEN='<from .env>'; python -m analysis_service
```

**Dashboard.**

```bash
cd frontend
npm ci
npm run dev                       # http://localhost:5173, proxies /api/* to the local services
npm run dev:demo                  # demo mode: bundled synthetic data, no backend needed
```

## Tests

Tests start their own throwaway PostgreSQL and Kafka containers with Testcontainers, so they only
need a running Docker engine:

```bash
(cd backend/ingestion-service && ./mvnw test)
(cd backend/incident-service && ./mvnw test)
(cd analysis-service && pytest && ruff check . && ruff format --check .)
(cd frontend && npm run lint && npm run typecheck && npm test && npm run build && npm run build:demo)
```

The complete list, including the infrastructure checks, image builds and the smoke test:
[ci.md](ci.md#running-the-checks-locally).
