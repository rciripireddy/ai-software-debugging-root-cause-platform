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
├── api/            routes, problem details, correlation ID middleware
├── services/       load incident data, run the engine, store results
├── analysis/       the engine (pure functions): normalization, stacktrace, features,
│                   classification, temporal, similarity, candidates, confidence, engine
├── clients/        read-only clients for the incident and ingestion APIs
├── repositories/   connection pool, SQL migrations (migrations/V*.sql), analysis storage
├── models/         input records and result model
├── config.py       environment variables
├── logging_config.py
├── main.py         application factory
└── __main__.py     `python -m analysis_service` (uvicorn with the service's logging)
evaluation/         evaluation on the labelled synthetic scenarios (not in the image)
tests/
```

## Local development

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate    macOS/Linux: source .venv/bin/activate
pip install -e ".[dev]"

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
python -m analysis_service
```

Then open <http://localhost:8000/docs>. If port 8000 is taken by the Compose container, set
`SERVER_PORT=8010`.
