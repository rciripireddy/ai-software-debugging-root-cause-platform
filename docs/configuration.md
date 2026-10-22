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
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers (`kafka:29092` inside Docker Compose) |
| `KAFKA_TOPIC_ERROR_EVENTS` | `error-events.v1` | Topic for `ErrorEventCreated` messages |
| `KAFKA_TOPIC_PARTITIONS` | `3` | Partitions used when the services create the topics |
| `KAFKA_TOPIC_ERROR_EVENTS_DLT` | `error-events.v1.dlt` | incident-service only: dead-letter topic |
| `KAFKA_CONSUMER_CONCURRENCY` | `3` | incident-service only: consumer threads (at most one per partition is useful) |
| `CORRELATION_MINIMUM_SEVERITY` | `ERROR` | incident-service only: events below this severity create no incidents |

Other settings in each `application.yml`:

- Flyway migrates the service's schema (`ingestion` or `incident`) on startup.
- Hibernate validates the entity mappings against the schema (`ddl-auto: validate`) and never
  changes the schema itself.
- The connection pool waits at most 5 seconds for a database connection, so an unreachable
  database is reported quickly: API calls return `503` and readiness reports `DOWN`.
- Actuator exposes only `health` and `info`. The readiness group includes the database check,
  but not Kafka (see [architecture.md](architecture.md#health-checks)).

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

## Service configuration

| Service | File | Notable settings |
| --- | --- | --- |
| ingestion-service | `backend/ingestion-service/src/main/resources/application.yml` | See above |
| incident-service | `backend/incident-service/src/main/resources/application.yml` | See above |
| analysis-service | `analysis-service/Dockerfile` | Listens on port `8000` |
| frontend | `frontend/vite.config.ts`, `frontend/nginx.conf` | API proxy routes for development and Docker |

Spring Boot maps environment variables to properties, so any property can be overridden at
runtime, for example `SERVER_PORT=9081` or `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`.
