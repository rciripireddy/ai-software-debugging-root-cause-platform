import asyncio

import psycopg
import pytest

from analysis_service.repositories import database
from analysis_service.repositories.database import (
    Migration,
    MigrationError,
    load_migrations,
    migrate,
)

pytestmark = pytest.mark.database


def run_migrate(conninfo: str) -> list[int]:
    async def go() -> list[int]:
        async with await psycopg.AsyncConnection.connect(conninfo) as connection:
            return await migrate(connection)

    return asyncio.run(go())


def test_migrations_are_applied_once_and_recorded(settings):
    run_migrate(settings.database_conninfo)

    assert run_migrate(settings.database_conninfo) == []
    with psycopg.connect(settings.database_conninfo) as connection:
        rows = connection.execute(
            "SELECT version, description, checksum FROM analysis.schema_migrations ORDER BY version"
        ).fetchall()
        columns = {
            row[0]
            for row in connection.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema = 'analysis' AND table_name = 'incident_analyses'"
            )
        }
    migrations = load_migrations()
    assert [(v, c) for v, _, c in rows] == [(m.version, m.checksum) for m in migrations]
    assert {
        "incident_id",
        "revision",
        "analysis_version",
        "generated_at",
        "summary",
        "confidence",
        "primary_category",
        "primary_title",
        "result",
    } <= columns


def test_changed_migration_is_rejected(settings, monkeypatch):
    run_migrate(settings.database_conninfo)
    original = load_migrations()
    changed = [Migration(m.version, m.description, m.sql + "\n-- edited") for m in original]
    monkeypatch.setattr(database, "load_migrations", lambda: changed)

    with pytest.raises(MigrationError, match="V1 was changed"):
        run_migrate(settings.database_conninfo)


def test_migration_files_are_versioned_sql():
    migrations = load_migrations()

    assert [m.version for m in migrations] == list(range(1, len(migrations) + 1))
    assert all(m.sql.strip() for m in migrations)
