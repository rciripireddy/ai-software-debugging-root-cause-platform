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

## Service configuration

| Service | File | Notable settings |
| --- | --- | --- |
| ingestion-service | `backend/ingestion-service/src/main/resources/application.yml` | `SERVER_PORT` (default `8081`); Actuator exposes `health` and `info` only |
| incident-service | `backend/incident-service/src/main/resources/application.yml` | `SERVER_PORT` (default `8082`); Actuator exposes `health` and `info` only |
| analysis-service | `analysis-service/Dockerfile` | Listens on port `8000` |
| frontend | `frontend/vite.config.ts`, `frontend/nginx.conf` | API proxy routes for development and Docker |

Spring Boot maps environment variables to properties, so any property can be overridden at
runtime, for example `SERVER_PORT=9081` or `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`.
