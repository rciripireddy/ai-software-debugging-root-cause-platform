# database

PostgreSQL setup for local development.

- `init/` is mounted into the Postgres container at `/docker-entrypoint-initdb.d`. Scripts there run
  **only when the data volume is created for the first time**.
- `01-create-schemas.sql` creates one schema per backend service (`ingestion`, `incident`).

Tables are **not** created here. Each Spring Boot service owns its schema and creates its tables
with Flyway migrations when it starts:

| Schema | Migrations |
| --- | --- |
| `ingestion` | `backend/ingestion-service/src/main/resources/db/migration/` |
| `incident` | `backend/incident-service/src/main/resources/db/migration/` |

The data model, constraints and indexes are described in [docs/database.md](../docs/database.md).

To start over with an empty database, remove the volume (this deletes all local data):

```bash
docker compose down -v
docker compose up -d
```

Connect with `psql` from inside the container:

```bash
docker compose exec postgres psql -U rootcause -d rootcause -c '\dt ingestion.*'
```
