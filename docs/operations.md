# Operations

How the platform runs in Docker: containers and networks, starting and stopping, data, logs,
health checks, metrics, monitoring with Prometheus and Grafana, and what to do when something
fails. The behaviour described in [Failure and recovery](#failure-and-recovery) was tested on the
local Compose stack. Automated checks of the stack (GitHub Actions) are described in
[ci.md](ci.md).

## Containers

| Service | Image | Runs as | Root filesystem | Memory limit | Published port (127.0.0.1 only) |
| --- | --- | --- | --- | --- | --- |
| `frontend` | nginx (unprivileged) with the built dashboard | uid 101 | read-only | 64 MB | 3000 (dashboard) |
| `ingestion-service` | Java 21 JRE (Alpine), layered Spring Boot jar | uid 10001 | read-only | 768 MB | 8081 (development) |
| `incident-service` | Java 21 JRE (Alpine), layered Spring Boot jar | uid 10001 | read-only | 768 MB | 8082 (development) |
| `analysis-service` | Python 3.12 (slim), virtualenv from `requirements.lock` | uid 10001 | read-only | 256 MB | 8000 (development) |
| `postgres` | `postgres:17-alpine` | postgres (entrypoint starts as root) | read-only + data volume | 512 MB | 5432 (development) |
| `kafka` | `apache/kafka:4.3.1` (KRaft, single node) | appuser | **writable** | 1 GB (heap 256–512 MB) | 9092 (development) |
| `prometheus` | `prom/prometheus:v3.5.0` | nobody | read-only + data volume | 256 MB | 9090 |
| `grafana` | `grafana/grafana:12.1.1` | uid 472 | read-only + data volume | 256 MB | 3001 |

Every container drops all Linux capabilities (PostgreSQL keeps the five its entrypoint needs to
prepare the data directory and switch to the `postgres` user: `CHOWN`, `DAC_OVERRIDE`, `FOWNER`,
`SETGID`, `SETUID`), runs with `no-new-privileges`, and none is privileged. Containers with a
read-only root filesystem get a small `/tmp` tmpfs (Tomcat's work directory, nginx's temporary
files); PostgreSQL also gets one for its socket directory. **Kafka stays writable** because its
image writes the broker configuration from the environment at startup.

Images:

- The Java images are built in two stages: Maven builds the jar, which is split into Spring Boot
  layers (dependencies, loader, snapshot dependencies, application) so a code change only
  rebuilds the small application layer. The runtime stage has only the JRE. The heap is capped at
  60% of the container memory limit (`-XX:MaxRAMPercentage=60`) and the JVM exits on
  `OutOfMemoryError`, so Docker restarts it.
- analysis-service installs exact versions from `analysis-service/requirements.lock` into a
  virtualenv in a build stage and copies only that virtualenv into the runtime image, so two
  builds of the same commit get the same packages.
- The dashboard is built with `npm ci` (from `package-lock.json`) and served by nginx.
- Application code in every image is owned by root and read-only for the service user.

### Networks

| Network | Members | Purpose |
| --- | --- | --- |
| `frontend` | frontend, the three application services | the dashboard proxy reaches the APIs |
| `backend` | the three application services, PostgreSQL, Kafka | data access |
| `monitoring` | Prometheus, Grafana, the three application services | metric scrapes |

The dashboard proxy cannot reach PostgreSQL or Kafka, and Prometheus and Grafana cannot reach
them either (checked with `nc` from inside the containers).

### Ports

`docker-compose.yml` is the production-style setup: it publishes only the dashboard (3000),
Prometheus (9090) and Grafana (3001), all on `127.0.0.1`. `docker-compose.override.yml`, which
`docker compose` loads automatically, adds **development ports** for the three services (8081,
8082, 8000), PostgreSQL (`POSTGRES_PORT`, default 5432) and Kafka (9092), also on `127.0.0.1`.
They are used by the backend tests' manual checks, the sample scripts, the evaluation runner and
tools such as `psql`. To run without them:

```bash
docker compose -f docker-compose.yml up -d
```

## Starting and stopping

```bash
docker compose up -d --build   # build and start (first start: about 1-2 minutes)
docker compose ps              # wait until every service is "healthy"
docker compose logs -f incident-service
docker compose stop            # stop, keep containers and data
docker compose down            # remove containers and networks, KEEP the volumes
docker compose down -v         # also delete all data (PostgreSQL, Kafka, Prometheus, Grafana)
```

**`docker compose down` keeps all data**: the named volumes `postgres-data`, `kafka-data`,
`prometheus-data` and `grafana-data` survive it, and the next `up` continues with the same
events, incidents, analyses, accounts, Kafka offsets and metrics history (verified: the row
counts were identical before and after `down`/`up`). Only `down -v` (or `docker volume rm`)
deletes them; after that, the next start creates empty schemas and the bootstrap accounts again.

Start order is handled by `depends_on` with health conditions, but the services do not rely on it:

- **PostgreSQL late or restarting**: the Spring Boot services retry the database connection in
  Flyway (`connect-retries: 12`, up to 10 s apart) before giving up; analysis-service retries its
  migrations 10 times. A service started 40 s before PostgreSQL became healthy without being
  restarted.
- **Kafka late or restarting**: ingestion-service accepts and stores events regardless (they wait
  in the outbox). incident-service starts its Kafka listener only after the application is ready
  and retries every 10 s while that fails (`action=kafka.listener_start_failed`), so it starts and
  serves its API even when the broker's host name cannot be resolved, and begins consuming as soon
  as Kafka is reachable. (Before this, a stopped Kafka container made incident-service fail at
  startup and restart in a loop.)
- **incident-service or ingestion-service down**: analysis-service starts and serves stored
  analyses; only a new analysis returns `503`.
- **A backend down**: nginx resolves the services per request, so the dashboard starts and
  reports the unavailable service itself.

### Graceful shutdown

On `docker compose stop` (SIGTERM; all processes run as PID 1 in exec form):

- The Spring Boot services stop accepting requests, let in-flight requests finish (graceful
  shutdown, up to 20 s per phase), stop the Kafka listener after the current record, let a
  running outbox batch finish (up to 10 s), then close the connection pool. Measured: about 1 s.
- analysis-service (uvicorn) finishes in-flight requests for up to 10 s, then closes its HTTP
  client and database pool. Measured: about 1.3 s.
- `stop_grace_period` is 30 s for the Java services, PostgreSQL and Kafka and 20 s for
  analysis-service, so Docker never has to kill them during a normal stop.

The JVM reports exit code 143 (terminated by SIGTERM) after a clean shutdown; that is expected.

## Logs

Every container logs to stdout. Docker's `json-file` driver keeps at most **3 files of 10 MB per
container** (`x-logging` in `docker-compose.yml`), so logs cannot fill the disk; older lines are
discarded. `docker compose logs` shows what is kept.

```bash
docker compose logs -f ingestion-service incident-service analysis-service
docker compose logs --since 10m incident-service | grep '"level":"ERROR"'
docker compose logs frontend | grep <correlation-id>
```

All application logs are one JSON object per line with
[Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html) fields: timestamp,
level, logger, `service.name`, `service.version`, `service.environment` (`APP_ENVIRONMENT`,
default `local`) and `correlationId`, plus an `action` field and identifiers for business events
(see [architecture.md](architecture.md#logging)). The Spring Boot services write ECS as nested
objects (`"log":{"level":…}`), analysis-service and nginx as dotted keys (`"log.level"`); both
are the same fields to ECS tools.

The nginx access log has the same format (`event.dataset: nginx.access`) with method, path
(without the query string), status, duration, upstream and correlation ID. Requests for static
assets are not logged.

**Never logged**: passwords, access tokens, API keys, service tokens, the `Authorization` header,
CSRF tokens, cookies, request bodies, log messages and stack traces received from monitored
applications. A scan of 4,344 log lines from all containers found none of the configured secret
values and no JWT.

### Correlation IDs

nginx keeps a client's `X-Correlation-Id` when it matches `[A-Za-z0-9._:-]{1,128}` and otherwise
uses its own request ID; it passes the value on to the service. Each service applies the same
rule (generating a UUID if needed), puts the ID on every log line of the request, returns it in
the `X-Correlation-Id` response header and in error responses, forwards it on its own HTTP calls
(analysis-service → incident-service, ingestion-service) and in the `correlation-id` Kafka header
(ingestion-service → incident-service). One ID therefore links the nginx access log, the API
request, the outbox publication, the incident update and any analysis. Example:

```bash
docker compose logs --no-log-prefix | grep phase7-related-check   # nginx, analysis, incident and ingestion lines
```

### Errors

- Expected failures (validation errors, 401/403, 404, rate limits, unavailable database or
  dependency) are logged as one line with `action`, status and reason, without a stack trace.
- Unexpected errors are logged with `action=http.unexpected_error`, the method, path, error type
  and the stack trace (`error.stack_trace`), and answered with `500` and the correlation ID.
- Kafka and outbox failures are logged with the root cause only (`event.process_failed`,
  `event.dead_letter`, `outbox.publish_failed`, `outbox.poll_failed`).

## Health model

| Probe | Meaning | ingestion-service | incident-service | analysis-service |
| --- | --- | --- | --- | --- |
| Liveness | The process works; restarting it would not help | `/actuator/health/liveness` | `/actuator/health/liveness` | `/health/liveness` (also `/health`) |
| Readiness | The service can do its work | `/actuator/health/readiness`: database | `/actuator/health/readiness`: database | `/health/readiness`: database |

Decisions:

- **Liveness never checks dependencies.** A database outage must not get working processes
  restarted; the services recover on their own when the database returns.
- **Kafka is not part of any readiness check.** ingestion-service keeps accepting events during a
  Kafka outage (they wait in the transactional outbox), and incident-service keeps serving its
  API; reporting them as not ready would stop exactly the traffic that still works. A Kafka
  outage is visible in the outbox metrics instead.
- **Other HTTP services are not part of readiness.** analysis-service needs incident-service and
  ingestion-service only while an analysis runs; without them that request returns `503` and
  stored analyses can still be read.

Docker health checks call the readiness endpoints (interval 15 s, timeout 5 s, 4 retries; a
`start_period` of 90 s for the Java services, with checks every 5 s during it, so a normal start
is reported healthy after about 20 s). **Docker does not restart unhealthy containers**: during a
database outage the services are marked `unhealthy` and keep running, and become `healthy` again
by themselves. Containers are restarted (`restart: unless-stopped`) only when the process exits,
for example after an `OutOfMemoryError`. Liveness endpoints are there for orchestrators that do
restart on a failed liveness probe.

The dashboard's Service Health page calls the same readiness endpoints, so "Healthy" means the
service can do its work, not only that the process runs.

## Metrics

| Service | Endpoint | Format |
| --- | --- | --- |
| ingestion-service | `GET /actuator/prometheus` (port 8081) | Prometheus text (Micrometer) |
| incident-service | `GET /actuator/prometheus` (port 8082) | Prometheus text (Micrometer) |
| analysis-service | `GET /metrics` (port 8000) | Prometheus text (`prometheus-client`) |

**Access**: the endpoints need no credentials but are internal: the dashboard proxy answers `404`
for them (`/api/*/actuator/prometheus`, `/api/analysis/metrics`, and every actuator endpoint
other than health), and the production-style Compose file does not publish the service ports.
In development they are reachable on `127.0.0.1` only. Spring Boot exposes only the `health`,
`info` and `prometheus` actuator endpoints.

**Labels** have small fixed value sets: HTTP metrics use the route template
(`/api/v1/incidents/{id}`), never the requested path; there are no event, incident or user IDs,
usernames, messages or URLs in labels.

| Metric | Service | Labels | What it counts |
| --- | --- | --- | --- |
| `http_server_requests_seconds` (histogram) | all three | `method`, `uri`, `status`, `outcome` | HTTP requests and latency; the same buckets (5 ms–10 s) in every service |
| `rcp_ingestion_events_accepted_total` | ingestion | `severity` | events stored together with their outbox message |
| `rcp_outbox_published_total` | ingestion | — | outbox messages acknowledged by Kafka |
| `rcp_outbox_publish_failures_total` | ingestion | — | failed publish attempts (retried later) |
| `rcp_outbox_pending_messages` | ingestion | — | messages not yet published (refreshed every 15 s) |
| `rcp_outbox_oldest_pending_age_seconds` | ingestion | — | age of the oldest unpublished message; 0 when the outbox is empty |
| `rcp_events_consumed_total` | incident | `outcome`: `incident_created`, `incident_updated`, `duplicate`, `ignored_severity`, `already_assigned` | Kafka messages processed |
| `rcp_events_consumer_retries_total` | incident | — | failed processing attempts that are retried |
| `rcp_events_dead_lettered_total` | incident | — | messages sent to the dead-letter topic |
| `rcp_analyses_total` | analysis | `result`: `success`, `not_found`, `failed` | analyses requested |
| `rcp_analysis_duration_seconds` (histogram) | analysis | — | time to run and store an analysis |
| `rcp_backend_request_failures_total` | analysis | `service` | failed calls to incident-service or ingestion-service |
| `rcp_db_pool_connections`, `rcp_db_pool_requests_waiting`, `rcp_db_pool_connection_errors_total` | analysis | `state` (`in_use`, `idle`) | PostgreSQL connection pool |
| `hikaricp_connections_*` | both Spring services | `pool` | connection pool: active, idle, pending, timeouts, acquire time |
| `jvm_*`, `process_*`, `kafka_*` | Spring services | | JVM memory and GC, CPU, Kafka client metrics |
| `process_*`, `python_info` | analysis | | memory, CPU and open files |

SQL statements are not logged or timed individually (no verbose SQL logging); connection
acquisition time and pool usage come from the pool metrics.

Example, from the verification run (10 sample events, 4 incidents analyzed): the counters changed
by exactly the activity — `rcp_ingestion_events_accepted_total` +10 (7 error, 2 warn, 1 info),
`rcp_outbox_published_total` +10, `rcp_events_consumed_total` +10 (4 `incident_created`,
3 `incident_updated`, 3 `ignored_severity`), `rcp_analyses_total{result="success"}` +4,
matching the 10 events, 4 incidents and 4 analyses in the database.

## Prometheus and Grafana

| | URL | Notes |
| --- | --- | --- |
| Prometheus | <http://localhost:9090> | targets on *Status → Targets*, alert rules on *Alerts*; 7 days / 1 GB retention |
| Grafana | <http://localhost:3001> | the *Root Cause Platform* dashboard opens as the home page; anonymous users can view, the `admin` account (`GRAFANA_ADMIN_PASSWORD`) can edit |

Configuration is in the repository and loaded at startup, without manual setup:

- `monitoring/prometheus/prometheus.yml`: scrapes the three services every 15 s.
- `monitoring/prometheus/alerts.yml`: `ServiceDown`, `OutboxBacklogAging` (oldest unpublished
  event older than 5 minutes), `EventsDeadLettered` and `HighServerErrorRate` (more than 5% 5xx).
  The rules are evaluated and shown in Prometheus; no Alertmanager is configured, so nothing is
  sent anywhere.
- `monitoring/grafana/provisioning/`: the Prometheus data source and the dashboard provider.
- `monitoring/grafana/dashboards/root-cause-platform.json`: scrape status; incidents created,
  events accepted and analyses run in the selected range; request rate, 5xx rate and p95 latency
  per service; events accepted by severity; outbox backlog; Kafka publishing; events consumed by
  outcome; consumer retries and dead letters; analyses by result and duration; database
  connections; JVM heap.

Both are bound to `127.0.0.1`. **Do not publish them without authentication**: Prometheus has no
access control, and Grafana's anonymous viewer access is meant for a local machine.

## Resource use

Measured with `docker stats` on the local stack (Docker Desktop, WSL 2), idle after the
verification runs:

| Container | Memory used / limit | CPU (idle) |
| --- | --- | --- |
| ingestion-service | 317 MiB / 768 MiB | ~4% |
| incident-service | 319 MiB / 768 MiB | ~5% |
| analysis-service | 51 MiB / 256 MiB | ~1% |
| frontend | 5 MiB / 64 MiB | 0% |
| postgres | 61 MiB / 512 MiB | ~5% |
| kafka | 357 MiB / 1 GiB | ~4% |
| prometheus | 46 MiB / 256 MiB | <1% |
| grafana | 141 MiB / 256 MiB | <1% |

Before these limits, Kafka used about 657 MiB with its default 1 GB heap and each Java service
about 408 MiB. These are single measurements on one machine, not a load test; raise the limits in
`docker-compose.yml` for larger volumes. Image sizes: Java services 450 MB, analysis-service
253 MB, frontend 91 MB.

## Failure and recovery

Each scenario was run against the local stack (Phase 7 verification).

| Failure | What happens | Recovery |
| --- | --- | --- |
| **PostgreSQL stopped** | Readiness of all three services fails within about 5 s (`503`); liveness stays `UP`; APIs answer `503` problem responses; Docker marks the services `unhealthy` after about a minute but does not restart them; the dashboard shows "unavailable" messages | Automatic: readiness `UP` within about 7 s of PostgreSQL being healthy, Docker health `healthy` within about 12 s, no container restarted |
| **Kafka stopped** | Events are still accepted (`201`) and stored; they wait in the outbox, visible as `rcp_outbox_pending_messages` and a growing `rcp_outbox_oldest_pending_age_seconds`; failed attempts are counted and retried with backoff; readiness stays `UP` | Automatic: the backlog is published and consumed within about 10 s of Kafka returning; no event lost, no duplicate count |
| **incident-service stopped** | analysis-service answers `503` ("incident-service is temporarily unavailable") for new analyses, counts the failure, and still serves stored analyses; ingestion keeps accepting events, which wait in Kafka; the dashboard proxy returns `502` for incident calls and the pages say so | Automatic: after the restart the consumer processes the waiting messages |
| **analysis-service stopped** | Ingestion, Kafka and incident correlation continue unchanged; only analysis pages are unavailable | Restart; nothing to catch up |
| **frontend restarted** | The backend pipeline is unaffected (events accepted and correlated during the restart) | Serves again within about 3 s |
| **Any service restarted** | `docker compose restart <service>` for each service, Kafka and PostgreSQL: everything healthy again within 6–19 s and the pipeline works, without manual cleanup | — |
| **Services started while PostgreSQL is down** | They keep running and waiting (Flyway / migration retries); not restarted by Docker | Healthy about 20 s after PostgreSQL is up |
| **Services started while Kafka is stopped** | Both Spring services start and are ready; ingestion accepts events into the outbox; incident-service retries starting its consumer every 10 s | The waiting events are processed after Kafka returns (about 1 minute, mostly the outbox retry backoff) |
| **Invalid configuration** | The service stops at startup with a list of the invalid variables (values not shown) | Fix `.env` and start again |
| **A Kafka message delivered twice** | The second delivery is recognised as a duplicate (`rcp_events_consumed_total{outcome="duplicate"}`) and the occurrence count does not change | — |

### Troubleshooting

| Symptom | Check |
| --- | --- |
| A service stays `unhealthy` | `curl localhost:8081/actuator/health/readiness` (or 8082, `localhost:8000/health/readiness`): `DOWN` means the database is unreachable; then `docker compose ps postgres` and `docker compose logs postgres` |
| A service keeps restarting | `docker compose logs <service>`: an "Invalid configuration" message lists the environment variables to fix; `OutOfMemoryError` means the memory limit is too low |
| Compose refuses to start ("is not set") | A required secret is missing in `.env`; see `.env.example` |
| Events accepted but no incidents appear | Outbox backlog (`rcp_outbox_pending_messages`, Grafana *Outbox* panel) means Kafka is unreachable: `docker compose ps kafka`. No backlog but no incidents: `docker compose logs incident-service` for `event.process_failed` / `event.dead_letter` |
| An API call failed | Take the `correlationId` from the error response and `docker compose logs --no-log-prefix \| grep <id>` |
| Analyses fail with 503 | `rcp_backend_request_failures_total` shows which service; check its health |
| Prometheus target down | *Status → Targets* in Prometheus shows the error; the service may be restarting |
| Port already in use | Change the `*_PORT` variable in `.env` |

## Configuration validation

The services check their critical settings at startup and stop with a list of what is wrong,
naming the environment variable but never its value:

- Spring Boot services: database address (`DB_HOST`, `DB_PORT`, `DB_NAME`),
  `KAFKA_BOOTSTRAP_SERVERS` (`host:port` list), `SERVER_PORT`, and the secrets
  (`AUTH_JWT_SECRET`, `INTERNAL_SERVICE_TOKEN`, and `INGESTION_API_KEY` for ingestion-service),
  which must be at least 32 characters. Reported before the application context starts:
  `The ingestion-service configuration is invalid: - KAFKA_BOOTSTRAP_SERVERS must be a
  comma-separated list of host:port entries …`
- analysis-service: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_PASSWORD`, `INCIDENT_SERVICE_URL`,
  `INGESTION_SERVICE_URL` (http/https URLs) and the two secrets; exits with code 2 and an
  `action=config.invalid` log line listing the problems.

Compose itself refuses to start while a required secret is missing from `.env`.
