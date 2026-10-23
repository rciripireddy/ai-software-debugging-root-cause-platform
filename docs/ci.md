# Continuous integration

GitHub Actions validates the repository with one workflow,
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml). It runs on every push to `main`, every
pull request targeting `main`, and on demand (*Actions → CI → Run workflow*). The workflow deploys
and publishes nothing: no images are pushed, no registry or cloud credentials are involved. The
public demo is built and published separately by Cloudflare Pages from the same repository
([deployment.md](deployment.md)).

CI is self-contained. Besides the code, it only downloads packages and public base images (npm,
Maven Central, PyPI, Docker Hub); no test calls an external API or a real system, and no real
credential is used.

## Jobs

The seven jobs run in parallel, each as its own check on a pull request:

| Check | Runs | Fails when |
| --- | --- | --- |
| **Frontend** | Node.js from `frontend/.node-version`; `npm ci`, `npm run lint`, `npm run typecheck`, `npm test` (Vitest), `npm run build`, `npm run build:demo` (which ends with the public demo bundle check, see [deployment.md](deployment.md#build)) | ESLint errors, type errors, failing tests, a broken live or demo build, `package-lock.json` out of sync, network code, local addresses or secrets in the public demo bundle |
| **Ingestion Service** | `sh mvnw -B -ntp test` in `backend/ingestion-service`: unit tests and Testcontainers integration tests (PostgreSQL 17, Kafka 4.3) | compilation errors, failing unit or integration tests (API, security, migrations, outbox, metrics) |
| **Incident Service** | the same for `backend/incident-service` | the same, including Kafka consumption, idempotency, retries and dead letters, sign-in |
| **Analysis Service** | installs `requirements.lock` + `requirements-dev.lock`, then `ruff check .`, `ruff format --check .`, `pytest` (with a Testcontainers PostgreSQL) | lint errors, unformatted code, failing tests |
| **Infrastructure** | [`check-repository.sh`](../scripts/ci/check-repository.sh), [`check-compose.sh`](../scripts/ci/check-compose.sh), [`check-monitoring.sh`](../scripts/ci/check-monitoring.sh) | see below |
| **Docker Build** | `docker build` of the four application images (not pushed) | a broken Dockerfile, build context or image dependency install |
| **Smoke Test** | starts the stack and runs [`scripts/smoke_test.py`](../scripts/smoke_test.py) | the services do not start and become healthy together, or the end-to-end flow below breaks |

When a Java job fails, its Surefire reports are uploaded as an artifact
(`<service>-test-reports`, kept 7 days). Other jobs print everything needed in the log; when the
smoke test fails, the job also prints `docker compose ps` and the last 200 log lines per container.

**Infrastructure** checks:

- **No committed secrets** (`check-repository.sh`): fails if `.env` (or any `.env.*` except
  `.env.example`), private keys, keystores or credential files are tracked, if a tracked file
  contains a private key block, or if a secret in `.env.example` has a value. Also fails if
  `docker-compose.yml` requires a variable that `.env.example` does not list, or a configuration
  file the stack mounts is missing. This catches obvious mistakes only; for more, enable GitHub's
  free secret scanning and push protection (*Settings → Code security*).
- **Compose** (`check-compose.sh`): `docker compose config` for the development setup (base +
  override) and the production-style file alone, and the port rule from Phase 7: every published
  port is bound to `127.0.0.1`, and the production-style file publishes only the dashboard,
  Prometheus and Grafana.
- **Monitoring** (`check-monitoring.sh`): `promtool check config` (including the alert rules'
  PromQL), then Prometheus and Grafana are started on their own and their APIs must show every
  alert rule and scrape job loaded, the provisioned dashboard, and the provisioned datasource
  reaching Prometheus.

**Smoke Test**, through the dashboard's nginx proxy only, as a browser would reach the stack:
dashboard page and security headers, readiness of the three services, metrics not reachable
through the proxy, anonymous requests and a wrong password refused, admin sign-in, an event
refused without the API key and accepted with it, the incident created from it through the outbox
and Kafka, an analysis run and read back, and a viewer able to read but not run analyses.

### Compose files in CI

`docker compose` loads `docker-compose.override.yml` automatically, so CI names its files
explicitly:

- **Development** (`docker-compose.yml` + override): only validated (Infrastructure).
- **Production-style** (`docker-compose.yml` alone): validated, and used by the smoke test
  (`COMPOSE_FILE=docker-compose.yml`), so no development port is opened.
- The smoke test starts the dashboard, the three services, PostgreSQL and Kafka; **not Prometheus
  and Grafana**, which add nothing to that flow. Their configuration is validated by starting
  just those two in the Infrastructure job.
- CI stacks use their own project names (`rcp-ci`, `rcp-ci-monitoring`) and are removed with their
  volumes at the end, so running the checks locally never touches the development stack
  (`root-cause-platform`) or its data.

### Secrets in CI

The workflow uses **no GitHub secrets**. [`scripts/ci/generate-env.sh`](../scripts/ci/generate-env.sh)
writes a throwaway environment file to the runner's temporary directory (mode 600) with a new
random value for each secret (database password, token signing key, service token, ingestion API
key, the `ci-admin` and `ci-viewer` passwords, Grafana password) on every run. Each value is
registered as a log mask, nothing prints them, and they disappear with the runner. Because no
secrets are needed, pull requests from forks run every job as well.

The workflow token is read-only (`permissions: contents: read`), and the checkout does not keep
it (`persist-credentials: false`).

### Caching, concurrency and timeouts

- **Caches** hold downloads only, keyed by the lock files: npm (`package-lock.json`), Maven
  (`~/.m2/repository`, per `pom.xml`) and pip (both `requirements*.lock`). Build outputs (`dist/`,
  `target/`) and Docker layers are not cached, so every run builds and tests from source.
- **Concurrency**: a new push to a pull request cancels that pull request's run in progress. Runs
  on `main` are never cancelled or replaced; every commit on `main` is validated.
- **Timeouts**: 15 minutes (Frontend, Analysis Service, Infrastructure), 25 (each Java job), 30
  (Docker Build, Smoke Test), well above the local durations below, so a hung container fails the
  job instead of blocking it for hours.
- **Actions**: only GitHub's own (`actions/checkout`, `setup-node`, `setup-java`, `setup-python`,
  `upload-artifact`), on their current major versions.

### Testcontainers

No CI-specific configuration: GitHub's Ubuntu runners include Docker, which Testcontainers finds
through the default socket. Ryuk (which removes the test containers afterwards) stays enabled. The
tests use the same images as Compose (`postgres:17-alpine`, `apache/kafka:4.3.1`).

## Running the checks locally

The same commands, from the repository root. On Windows, run the shell scripts in Git Bash
(`sh ...`); the Maven Wrapper is `.\mvnw.cmd` in PowerShell.

```bash
# Frontend
cd frontend
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npm run build:demo
cd ..

# Spring Boot services (need a running Docker engine for Testcontainers)
(cd backend/ingestion-service && sh mvnw -B -ntp test)
(cd backend/incident-service && sh mvnw -B -ntp test)

# Analysis service, in a virtualenv
cd analysis-service
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.lock -r requirements-dev.lock
pip install --no-deps -e .
ruff check .
ruff format --check .
pytest
cd ..

# Infrastructure (the generated file holds throwaway values; delete it afterwards)
sh scripts/ci/check-repository.sh
sh scripts/ci/generate-env.sh /tmp/ci.env
sh scripts/ci/check-compose.sh /tmp/ci.env
sh scripts/ci/check-monitoring.sh /tmp/ci.env

# Images
docker build -t rcp/ingestion-service:ci backend/ingestion-service
docker build -t rcp/incident-service:ci backend/incident-service
docker build -t rcp/analysis-service:ci analysis-service
docker build -t rcp/frontend:ci frontend

# Smoke test (project rcp-ci; removes only its own containers and volumes)
docker compose -p rcp-ci -f docker-compose.yml --env-file /tmp/ci.env up -d --build \
  --wait --wait-timeout 600 frontend ingestion-service incident-service analysis-service
python scripts/smoke_test.py --env-file /tmp/ci.env
docker compose -p rcp-ci -f docker-compose.yml --env-file /tmp/ci.env down --volumes
```

While the development stack is running, its ports 3000, 9090 and 3001 are taken; generate the
file with other ports and point the smoke test at the dashboard's port:

```bash
FRONTEND_PORT=3100 PROMETHEUS_PORT=9190 GRAFANA_PORT=3101 sh scripts/ci/generate-env.sh /tmp/ci.env
python scripts/smoke_test.py --env-file /tmp/ci.env --url http://127.0.0.1:3100
```

### Local durations

Measured on the development machine (Windows 10, 4 CPUs, Docker Desktop), with dependency
caches already filled. These are **not** GitHub Actions timings, which will show on the first run.

| Job | Local duration |
| --- | --- |
| Frontend | about 2.7 min (`npm ci` 66 s, lint 29 s, typecheck 12 s, Vitest 40 s, both builds 15 s) |
| Ingestion Service | 2.2–3.1 min (66 tests) |
| Incident Service | 2.0–2.2 min (83 tests) |
| Analysis Service | about 1.5 min (fresh virtualenv 67 s, ruff < 1 s, pytest 21 s) |
| Infrastructure | 23 s |
| Docker Build | 3.2 min (`--no-cache`; the Maven download cache of the build was warm) |
| Smoke Test | 70 s until healthy (images already built), 4 s of checks |

On GitHub the longest job is expected to be **Docker Build** or **Smoke Test**: both build the two
Java images on a fresh runner, including their Maven dependency download.

## Recommended branch protection

Not configured automatically, and optional for a portfolio repository. In *Settings → Rules →
Rulesets* (or *Settings → Branches*), add a rule for `main`:

- **Require status checks to pass**: Frontend, Ingestion Service, Incident Service, Analysis
  Service, Infrastructure, Docker Build, Smoke Test (they can be selected after the workflow has
  run once).
- **Block force pushes** and **restrict deletions**.
- Optionally **require a pull request before merging** (with 0 required approvals when working
  alone), so every change to `main` is checked before it lands.

## Limitations

- The smoke test follows one event. The failure scenarios of Phase 7 (outages, restarts) are not
  repeated in CI; see [operations.md](operations.md#failure-and-recovery).
- Docker Build and Smoke Test each build the images: sharing them between jobs would mean
  uploading large images or adding a registry. This costs runner minutes, not wall time, because
  the jobs run in parallel.
- Docker Hub limits anonymous image pulls. A run that hits the limit fails while pulling and
  succeeds when re-run.
- `mvnw` is stored without the executable bit, so CI runs it as `sh mvnw`. To make `./mvnw` work
  on Linux and macOS checkouts, commit the mode change once:
  `git update-index --chmod=+x backend/ingestion-service/mvnw backend/incident-service/mvnw`.
- Grafana's image downloads its preinstalled plugins at startup; if that download fails, the
  monitoring check still passes, because the dashboard and the datasource do not depend on them.
