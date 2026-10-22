"""Connection pool and schema migrations.

Migrations are plain SQL files named like Flyway's (``V<version>__<description>.sql``) in the
``migrations`` package directory. They run at startup in one transaction, serialized by an
advisory lock, and are recorded in ``analysis.schema_migrations`` with a checksum. An applied
migration must never be edited: a changed checksum stops the service. A small runner is used
instead of Alembic because the schema is plain SQL and there is no ORM to generate it from.
"""

import asyncio
import hashlib
import logging
import re
from dataclasses import dataclass
from importlib.resources import files

from psycopg import AsyncConnection
from psycopg_pool import AsyncConnectionPool

log = logging.getLogger(__name__)

SCHEMA = "analysis"
_MIGRATION_FILE = re.compile(r"^V(?P<version>\d+)__(?P<description>\w+)\.sql$")
# Arbitrary constant identifying the migration lock among PostgreSQL advisory locks.
_MIGRATION_LOCK_ID = 7_314_002


class MigrationError(Exception):
    pass


@dataclass(frozen=True)
class Migration:
    version: int
    description: str
    sql: str

    @property
    def checksum(self) -> str:
        return hashlib.sha256(self.sql.encode("utf-8")).hexdigest()


def load_migrations() -> list[Migration]:
    migrations = []
    for resource in files("analysis_service.repositories").joinpath("migrations").iterdir():
        match = _MIGRATION_FILE.match(resource.name)
        if match:
            migrations.append(
                Migration(
                    version=int(match["version"]),
                    description=match["description"].replace("_", " "),
                    sql=resource.read_text(encoding="utf-8"),
                )
            )
    migrations.sort(key=lambda m: m.version)
    versions = [m.version for m in migrations]
    if len(set(versions)) != len(versions):
        raise MigrationError("Duplicate migration versions")
    return migrations


async def migrate(connection: AsyncConnection) -> list[int]:
    """Applies pending migrations; returns the versions applied now."""
    applied_now: list[int] = []
    async with connection.transaction():
        await connection.execute("SELECT pg_advisory_xact_lock(%s)", (_MIGRATION_LOCK_ID,))
        await connection.execute(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")
        await connection.execute(
            f"""CREATE TABLE IF NOT EXISTS {SCHEMA}.schema_migrations (
                    version     integer      PRIMARY KEY,
                    description varchar(200) NOT NULL,
                    checksum    char(64)     NOT NULL,
                    applied_at  timestamptz  NOT NULL DEFAULT now())"""
        )
        cursor = await connection.execute(
            f"SELECT version, checksum FROM {SCHEMA}.schema_migrations"
        )
        applied = {version: checksum for version, checksum in await cursor.fetchall()}
        for migration in load_migrations():
            if migration.version in applied:
                if applied[migration.version] != migration.checksum:
                    raise MigrationError(
                        f"Migration V{migration.version} was changed after it was applied"
                    )
                continue
            await connection.execute(migration.sql)
            await connection.execute(
                f"INSERT INTO {SCHEMA}.schema_migrations (version, description, checksum) "
                "VALUES (%s, %s, %s)",
                (migration.version, migration.description, migration.checksum),
            )
            applied_now.append(migration.version)
    return applied_now


async def open_database(
    conninfo: str, min_size: int, max_size: int, timeout: float, attempts: int
) -> AsyncConnectionPool:
    """Opens the pool and applies migrations, retrying while the database is starting up."""
    pool = AsyncConnectionPool(
        conninfo,
        min_size=min_size,
        max_size=max_size,
        timeout=timeout,
        open=False,
        name="analysis",
    )
    await pool.open(wait=False)
    for attempt in range(1, attempts + 1):
        try:
            async with pool.connection(timeout=timeout) as connection:
                applied = await migrate(connection)
            log.info(
                "Database schema is up to date",
                extra={"action": "db.migrate", "appliedVersions": applied, "schema": SCHEMA},
            )
            return pool
        except MigrationError:
            await pool.close()
            raise
        except Exception as exc:  # database not reachable yet
            if attempt == attempts:
                await pool.close()
                raise
            log.warning(
                "Database not available; retrying",
                extra={
                    "action": "db.migrate_retry",
                    "attempt": attempt,
                    "failureReason": type(exc).__name__,
                },
            )
            await asyncio.sleep(2)
    raise AssertionError("unreachable")
