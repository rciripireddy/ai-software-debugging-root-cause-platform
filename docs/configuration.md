# Configuration

## Docker Compose environment

`docker-compose.yml` reads variables from a `.env` file in the repository root. Create it from the
template:

```bash
cp .env.example .env        # Windows PowerShell: Copy-Item .env.example .env
```

`.env` is listed in `.gitignore` and must not be committed. `POSTGRES_PASSWORD` has no default:
Compose refuses to start until it is set.

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

Changing `POSTGRES_*` values after the first start has no effect on the existing database volume.
Run `docker compose down -v` to recreate it (this deletes local data).

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

Other settings in each `application.yml`:

- Flyway migrates the service's schema (`ingestion` or `incident`) on startup.
- Hibernate validates the entity mappings against the schema (`ddl-auto: validate`) and never
  changes the schema itself.
- The connection pool waits at most 5 seconds for a database connection, so an unreachable
  database is reported quickly: API calls return `503` and readiness reports `DOWN`.
- Actuator exposes only `health` and `info`. The readiness group includes the database check.

## Service configuration

| Service | File | Notable settings |
| --- | --- | --- |
| ingestion-service | `backend/ingestion-service/src/main/resources/application.yml` | See above |
| incident-service | `backend/incident-service/src/main/resources/application.yml` | See above |
| analysis-service | `analysis-service/Dockerfile` | Listens on port `8000` |
| frontend | `frontend/vite.config.ts`, `frontend/nginx.conf` | API proxy routes for development and Docker |

Spring Boot maps environment variables to properties, so any property can be overridden at
runtime, for example `SERVER_PORT=9081` or `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`.
