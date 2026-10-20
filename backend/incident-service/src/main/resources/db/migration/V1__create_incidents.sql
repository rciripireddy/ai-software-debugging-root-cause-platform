-- Incidents group occurrences of the same error (same fingerprint) within one application.
-- The ingestion schema is owned by ingestion-service, whose migrations must run first.
CREATE TABLE incident.incidents (
    id               uuid         PRIMARY KEY,
    application_id   uuid         NOT NULL REFERENCES ingestion.applications (id),
    title            varchar(255) NOT NULL,
    status           varchar(20)  NOT NULL,
    severity         varchar(10)  NOT NULL,
    first_seen_at    timestamptz  NOT NULL,
    last_seen_at     timestamptz  NOT NULL,
    occurrence_count integer      NOT NULL,
    fingerprint      varchar(64)  NOT NULL,
    summary          text,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_incidents_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    CONSTRAINT ck_incidents_severity CHECK (severity IN ('DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL')),
    CONSTRAINT ck_incidents_occurrence_count CHECK (occurrence_count > 0),
    CONSTRAINT ck_incidents_seen_order CHECK (first_seen_at <= last_seen_at)
);

-- At most one unresolved incident per error and application. Resolved incidents are kept
-- as history, so a recurring error can open a new incident later.
CREATE UNIQUE INDEX uq_incidents_active_fingerprint
    ON incident.incidents (application_id, fingerprint)
    WHERE status <> 'RESOLVED';

-- Incident list: filtering by status/severity, newest activity first.
CREATE INDEX ix_incidents_status_severity ON incident.incidents (status, severity);
CREATE INDEX ix_incidents_last_seen_at ON incident.incidents (last_seen_at DESC);
CREATE INDEX ix_incidents_application_id ON incident.incidents (application_id);

-- Error events that make up an incident. An event belongs to at most one incident.
CREATE TABLE incident.incident_events (
    incident_id    uuid        NOT NULL REFERENCES incident.incidents (id) ON DELETE CASCADE,
    error_event_id uuid        NOT NULL REFERENCES ingestion.error_events (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (incident_id, error_event_id),
    CONSTRAINT uq_incident_events_error_event UNIQUE (error_event_id)
);
