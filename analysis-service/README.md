# analysis-service

Python (FastAPI) service that analyzes incidents: it detects recurring patterns, finds related
incidents and ranks **potential root causes** with supporting evidence, a confidence band and
limitations. The analysis is deterministic and rule-based; it uses no machine-learning model and
no external AI service. Results are stored as revisions in the `analysis` schema.

- How the analysis works: [docs/analysis.md](../docs/analysis.md)
- API: [docs/api.md](../docs/api.md#analysis-service-port-8000)
- Settings: [docs/configuration.md](../docs/configuration.md#analysis-service)

## Layout

```text
src/analysis_service/
├── api/            routes, problem details, correlation ID and metrics middleware
├── services/       load incident data, run the engine, store results
├── analysis/       the engine (pure functions): normalization, stacktrace, features,
│                   classification, temporal, similarity, candidates, confidence, engine
├── clients/        read-only clients for the incident and ingestion APIs
├── repositories/   connection pool, SQL migrations (migrations/V*.sql), analysis storage
├── models/         input records and result model
├── config.py       environment variables and their startup validation
├── metrics.py      Prometheus metrics (GET /metrics)
├── logging_config.py
├── main.py         application factory
└── __main__.py     `python -m analysis_service` (uvicorn with the service's logging)
evaluation/         evaluation on the labelled synthetic scenarios (not in the image)
tests/
requirements.lock   exact dependency versions installed in the Docker image
requirements-dev.lock  exact test and lint tool versions (CI and local development)
```

The Docker image installs the versions in `requirements.lock` (multi-stage build, non-root
user). After changing dependencies in `pyproject.toml`, regenerate the file with the command in
its header so local tests and the image keep using the same versions; likewise
`requirements-dev.lock` after changing the `dev` extras.

## Local development

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate    macOS/Linux: source .venv/bin/activate
# Exact versions: requirements.lock is what the Docker image installs, requirements-dev.lock adds
# the test and lint tools (the same set CI uses; see docs/ci.md).
pip install -r requirements.lock -r requirements-dev.lock
pip install --no-deps -e .

pytest                 # unit tests; API/persistence tests start PostgreSQL with Testcontainers (Docker)
ruff check .           # lint
ruff format --check .  # formatting
python -m evaluation   # evaluation on the synthetic scenarios (no services needed)
```

Running the service on the host needs PostgreSQL, and incident-service and ingestion-service for
analysis requests (for example the Docker Compose stack):

```bash
# PowerShell: $env:DB_PASSWORD='...'; $env:DB_PORT='5432'
export DB_PASSWORD='<POSTGRES_PASSWORD>' DB_PORT='<POSTGRES_PORT>'
# The service does not start without its auth secrets (same values as in .env):
export AUTH_JWT_SECRET='<from .env>' INTERNAL_SERVICE_TOKEN='<from .env>'
python -m analysis_service
```

Then open <http://localhost:8000/docs>. The analysis endpoints need a signed-in user (bearer
token, see [docs/security.md](../docs/security.md)); running an analysis needs the `ADMIN` role. If port 8000 is taken by the Compose container, set
`SERVER_PORT=8010`.
