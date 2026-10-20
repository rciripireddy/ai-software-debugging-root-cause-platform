# database

PostgreSQL setup for local development.

- `init/` is mounted into the Postgres container at `/docker-entrypoint-initdb.d`. Scripts there run
  **only when the data volume is created for the first time**.
- `01-create-schemas.sql` creates one schema per backend service (`ingestion`, `incident`).

No tables exist yet. Application tables will be created by each service's own migrations once
log ingestion (Phase 2) and incident correlation (Phase 3) are implemented.

To re-run the init scripts, remove the volume (this deletes all local data):

```bash
docker compose down -v
docker compose up -d postgres
```

Connect with `psql` from inside the container:

```bash
docker compose exec postgres psql -U rootcause -d rootcause -c '\dn'
```
