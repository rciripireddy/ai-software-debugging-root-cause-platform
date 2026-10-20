# analysis-service

Python (FastAPI) service that will analyze incidents and suggest likely root causes.

**Current state (Phase 1):** only a `GET /health` endpoint. No analysis logic is implemented yet.

## Local development

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate    macOS/Linux: source .venv/bin/activate
pip install -e ".[dev]"

pytest                 # run tests
ruff check .           # lint
uvicorn analysis_service.main:app --reload --port 8000
```

Then open <http://localhost:8000/health> or the interactive API docs at <http://localhost:8000/docs>.
