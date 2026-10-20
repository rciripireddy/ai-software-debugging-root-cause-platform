-- Runs once, when the PostgreSQL volume is first initialized.
-- Each backend service owns its own schema; tables are added by the services
-- themselves through versioned migrations in later phases.

CREATE SCHEMA IF NOT EXISTS ingestion;
CREATE SCHEMA IF NOT EXISTS incident;
