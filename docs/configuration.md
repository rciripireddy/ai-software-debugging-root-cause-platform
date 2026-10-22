# Configuration

## Docker Compose environment

`docker-compose.yml` reads variables from a `.env` file in the repository root. Create it from the
template:

```bash
cp .env.example .env        # Windows PowerShell: Copy-Item .env.example .env
```

`.env` is listed in `.gitignore` and must not be committed. `POSTGRES_PASSWORD`,
`AUTH_JWT_SECRET`, `INTERNAL_SERVICE_TOKEN` and `INGESTION_API_KEY` have no default: Compose
refuses to start until they are set.

| Variable | Default | Description |
| --- | --- | --- |
| `POSTGRES_DB` | `rootcause` | Database created on first start |
| `POSTGRES_USER` | `rootcause` | Database superuser for local development |
| `POSTGRES_PASSWORD` | *(required)* | Password for `POSTGRES_USER` |
| `POSTGRES_PORT` | `5432` | Host port for PostgreSQL |
| `KAFKA_CLUSTER_ID` | example ID | Base64 UUID identifying the KRaft cluster |
| `KAFKA_EXTERNAL_PORT` | `9092` | Host port for Kafka clients outside Docker |
| `INGESTION_SERVICE_PORT` | `8081` | Host port for the ingestion service |
| `INCIDENT_SERVICE_PORT` | `8082` | Host port for the incident service |
| `ANALYSIS_SERVICE_PORT` | `8000` | Host port for the analysis service |
| `FRONTEND_PORT` | `3000` | Host port for the dashboard |
| `AUTH_JWT_SECRET`, `INTERNAL_SERVICE_TOKEN`, `INGESTION_API_KEY` | *(required)* | See [Authentication](#authentication) |
| `AUTH_BOOTSTRAP_*`, `AUTH_TOKEN_TTL`, `AUTH_COOKIE_SECURE`, rate limits | see below | See [Authentication](#authentication) |

Changing `POSTGRES_*` values after the first start has no effect on the existing database volume.
Run `docker compose down -v` to recreate it (this deletes local data).

## Authentication

See [security.md](security.md) for how these are used. Secrets have no defaults anywhere; a
service whose secret is missing or shorter than 32 characters stops at startup with a message
that names the variable but not the value. Generate secrets with
`python -c "import secrets; print(secrets.token_urlsafe(48))"`.

| Variable | Default | Used by | Description |
| --- | --- | --- | --- |
| `AUTH_JWT_SECRET` | *(required)* | ingestion, incident, analysis | HS256 key that signs (incident-service) and verifies access tokens; at least 32 characters |
| `INTERNAL_SERVICE_TOKEN` | *(required)* | ingestion, incident, analysis | Internal credential analysis-service sends as `X-Service-Token`; at least 32 characters |
| `INGESTION_API_KEY` | *(required)* | ingestion | Key that log producers send as `X-Api-Key`; at least 32 characters |
| `AUTH_BOOTSTRAP_ADMIN_USERNAME`, `AUTH_BOOTSTRAP_ADMIN_PASSWORD` | `admin` (in `.env.example`), *(empty)* | incident | First `ADMIN`, created at startup if missing; no account is created while either is empty. Password: 12–72 bytes |
| `AUTH_BOOTSTRAP_VIEWER_USERNAME`, `AUTH_BOOTSTRAP_VIEWER_PASSWORD` | *(empty)* | incident | Optional `VIEWER`, created the same way |
| `AUTH_BOOTSTRAP_ADMIN_DISPLAY_NAME`, `AUTH_BOOTSTRAP_VIEWER_DISPLAY_NAME` | `Platform Admin`, `Dashboard Viewer` | incident | Names shown in the dashboard (service setting; not passed by Compose) |
| `AUTH_TOKEN_TTL` | `60m` | incident | Lifetime of an access token (and the session cookie) |
| `AUTH_COOKIE_SECURE` | `false` | incident | Mark the session cookie `Secure`; **set to `true` whenever the dashboard is served over HTTPS** |
| `AUTH_LOGIN_MAX_FAILURES_PER_USER` | `5` | incident | Failed sign-ins per username within the window before `429` |
| `AUTH_LOGIN_MAX_FAILURES_PER_CLIENT` | `20` | incident | Failed sign-ins per client address within the window before `429` |
| `AUTH_LOGIN_FAILURE_WINDOW` | `15m` | incident | Window for both sign-in limits |
| `INGESTION_RATE_LIMIT_PER_MINUTE` | `600` | ingestion | Accepted events per client address and minute |
| `ANALYSIS_RATE_LIMIT_PER_MINUTE` | `30` | analysis | Manual analyses per user and minute |

Issuer (`root-cause-platform`), audience (`root-cause-platform-api`) and the cookie name
(`rcp_session`) are fixed in each service's configuration (`app.auth.*` in `application.yml`,
`AUTH_ISSUER`/`AUTH_AUDIENCE`/`AUTH_COOKIE_NAME` in analysis-service) and must match across
services. The request body limit is `app.http.max-request-body` (`1MB`) in both Spring services
and `client_max_body_size` in nginx.

Docker Compose passes each secret only to the services that use it (see the table); PostgreSQL,
Kafka and the frontend receive none of them. The sample scripts read `INGESTION_API_KEY` and
`RCP_USERNAME`/`RCP_PASSWORD` from the environment, falling back to `.env` and its bootstrap admin
for local development.

## Spring Boot services

Both Spring Boot services read these environment variables. Docker Compose sets them from
`.env`; when running a service directly on the host, set at least `DB_PASSWORD`.

| Variable | Default | Description |
| --- | --- | --- |
| `DB_HOST` | `localhost` | PostgreSQL host (`postgres` inside Docker Compose) |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `rootcause` | Database name |
| `DB_USERNAME` | `rootcause` | Database user |
| `DB_PASSWORD` | *(empty)* | Database password. No default is stored in source files |
| `SERVER_PORT` | `8081` / `8082` | HTTP port of ingestion-service / incident-service |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(unset)* | `ecs` for JSON logs. Docker Compose sets it; unset gives plain-text logs |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers (`kafka:29092` inside Docker Compose) |
| `KAFKA_TOPIC_ERROR_EVENTS` | `error-events.v1` | Topic for `ErrorEventCreated` messages |
| `KAFKA_TOPIC_PARTITIONS` | `3` | Partitions used when the services create the topics |
| `KAFKA_TOPIC_ERROR_EVENTS_DLT` | `error-events.v1.dlt` | incident-service only: dead-letter topic |
| `KAFKA_CONSUMER_CONCURRENCY` | `3` | incident-service only: consumer threads (at most one per partition is useful) |
| `CORRELATION_MINIMUM_SEVERITY` | `ERROR` | incident-service only: events below this severity create no incidents |
| Authentication variables | — | See [Authentication](#authentication) |

Other settings in each `application.yml`:

- Flyway migrates the service's schema (`ingestion` or `incident`) on startup.
- Hibernate validates the entity mappings against the schema (`ddl-auto: validate`) and never
  changes the schema itself.
- The connection pool waits at most 5 seconds for a database connection, so an unreachable
  database is reported quickly: API calls return `503` and readiness reports `DOWN`.
- Actuator exposes only `health` and `info`, both public. The readiness group includes the
  database check, but not Kafka (see [architecture.md](architecture.md#health-checks)).
- Spring Boot's generated default user is disabled; requests are authenticated by the platform's
  own filter (see [security.md](security.md)).
- `server.forward-headers-strategy: native`: the client address and scheme come from
  `X-Forwarded-*` headers when they are sent by a private network address (the nginx proxy).

### Outbox publisher (ingestion-service, `app.outbox.*`)

| Property | Default | Description |
| --- | --- | --- |
| `publisher-enabled` | `true` | Run the background publisher |
| `poll-interval` | `1s` | Pause between publishing rounds |
| `batch-size` | `50` | Messages per round |
| `send-timeout` | `10s` | Wait for Kafka's acknowledgement of one message |
| `initial-backoff` / `max-backoff` | `1s` / `60s` | Retry delay after a failed send, doubling per attempt |
| `alert-every-attempts` | `10` | Log at `ERROR` every this many failed attempts of one message |
| `retention` | `7d` | Delete published outbox rows after this time |

The Kafka producer uses `acks=all` with idempotence enabled, and short client timeouts
(`max.block.ms` 5 s, `delivery.timeout.ms` 10 s) so a Kafka outage is detected quickly; the
outbox, not the producer, retries over longer periods.

### Kafka consumer (incident-service)

| Property | Default | Description |
| --- | --- | --- |
| `spring.kafka.consumer.group-id` | `incident-service` | Consumer group |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | A new group starts with the oldest retained message |
| `spring.kafka.listener.ack-mode` | `record` | Offsets are committed after each processed message |
| `app.kafka.consumer.retry.max-retries` | `3` | Retries after a failed attempt before dead-lettering |
| `app.kafka.consumer.retry.initial-interval` / `multiplier` / `max-interval` | `1s` / `2.0` / `10s` | Retry backoff |

## Analysis service

analysis-service reads environment variables (case-insensitive) through `pydantic-settings`.
Docker Compose sets the database variables from `.env`, the service URLs and `LOG_FORMAT=json`.
Durations accept seconds (`120`) or ISO-8601 (`PT2M`).

| Variable | Default | Description |
| --- | --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | `localhost`, `5432`, `rootcause`, `rootcause`, *(empty)* | PostgreSQL connection, same variables as the Spring Boot services. The service writes only to the `analysis` schema |
| `DB_POOL_MIN_SIZE` / `DB_POOL_MAX_SIZE` | `1` / `5` | Connection pool size |
| `DB_CONNECT_TIMEOUT` | `5` s | Connection and pool wait timeout |
| `DB_STARTUP_ATTEMPTS` | `10` | Attempts (2 s apart) to reach the database and apply migrations at startup |
| `INCIDENT_SERVICE_URL` | `http://localhost:8082` | incident-service base URL (`http://incident-service:8082` in Compose) |
| `INGESTION_SERVICE_URL` | `http://localhost:8081` | ingestion-service base URL (`http://ingestion-service:8081` in Compose) |
| `HTTP_TIMEOUT` | `5` s | Timeout of each call to those services |
| `HTTP_MAX_CONCURRENCY` | `8` | Maximum parallel calls to those services (shared by all requests) |
| `ANALYSIS_MAX_EVENT_DETAILS` | `20` | Newest events of the incident whose stack trace and source are read (1–50) |
| `ANALYSIS_RELATED_CANDIDATE_LIMIT` | `50` | Recently active incidents of the same application compared with it (0–100) |
| `ANALYSIS_BURST_WINDOW` | `15` minutes | Window for bursts and overlapping activity |
| `ANALYSIS_PRECEDING_WINDOW` | `2` minutes | How far back an error counts as "preceding" (time or trace link) |
| `ANALYSIS_RELATED_THRESHOLD` | `40` | Minimum similarity score for a related incident (1–100) |
| `AUTH_JWT_SECRET`, `INTERNAL_SERVICE_TOKEN`, `ANALYSIS_RATE_LIMIT_PER_MINUTE` | see [Authentication](#authentication) | Token verification, calls to the other services, analyses per user and minute |
| `LOG_FORMAT` | `text` | `json` for one ECS JSON object per line |
| `LOG_LEVEL` | `INFO` | Root log level |
| `SERVER_HOST` / `SERVER_PORT` | `0.0.0.0` / `8000` | Listen address |

The `ANALYSIS_*` windows and threshold change results for the same input. `analysisVersion`
identifies the rules in the code, not these settings, so stored analyses made with non-default
values are not marked as such (see [analysis.md](analysis.md#persistence-and-versioning)).

## Dashboard

The dashboard is a static build; these Vite variables are read **at build time** (for example
`VITE_REFRESH_LISTS_SECONDS=120 npm run build`). The defaults fit both the Vite dev server and the
nginx image, so Docker needs none of them.

| Variable | Default | Description |
| --- | --- | --- |
| `VITE_INGESTION_API_BASE` | `/api/ingestion` | Base path of the ingestion API |
| `VITE_INCIDENT_API_BASE` | `/api/incident` | Base path of the incident API |
| `VITE_ANALYSIS_API_BASE` | `/api/analysis` | Base path of the analysis API |
| `VITE_REFRESH_OVERVIEW_SECONDS` | `30` | Automatic refresh of the Overview page |
| `VITE_REFRESH_LISTS_SECONDS` | `60` | Automatic refresh of event and incident lists and incident details |
| `VITE_REFRESH_HEALTH_SECONDS` | `15` | Service Health checks |
| `VITE_REQUEST_TIMEOUT_SECONDS` | `10` | Timeout of each API request |

The base paths are relative on purpose: the browser only talks to the dashboard's own origin and
the proxy forwards `/api/<service>/…` to the services (see
[architecture.md](architecture.md#request-routing)), so no backend URL is compiled into the bundle.
The Root Cause Analysis page refreshes only on request or after an analysis is run.

**Demo mode** is selected with Vite's mode, not a variable: `npm run dev:demo` and
`npm run build:demo` (output in `frontend/dist-demo/`) serve bundled synthetic data and make no API
requests. The normal build does not contain the demo data. See
[frontend/src/demo/README.md](../frontend/src/demo/README.md).

## Service configuration

| Service | File | Notable settings |
| --- | --- | --- |
| ingestion-service | `backend/ingestion-service/src/main/resources/application.yml` | See above |
| incident-service | `backend/incident-service/src/main/resources/application.yml` | See above |
| analysis-service | `analysis-service/src/analysis_service/config.py` | See above; port `8000` |
| frontend | `frontend/src/config.ts`, `frontend/vite.config.ts`, `frontend/nginx.conf`, `frontend/security-headers.conf` | Dashboard settings above; API proxy routes for development and Docker; demo-mode alias; security headers |

Spring Boot maps environment variables to properties, so any property can be overridden at
runtime, for example `SERVER_PORT=9081` or `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`.
