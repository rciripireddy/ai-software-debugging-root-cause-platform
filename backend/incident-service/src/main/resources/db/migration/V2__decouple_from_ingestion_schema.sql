-- Phase 3: incident-service builds incidents from Kafka messages instead of reading the
-- ingestion schema. This migration copies the data it needs into its own tables, removes the
-- foreign keys into the ingestion schema and adds tracking of processed messages.

-- 1. Application details are stored on the incident itself.
ALTER TABLE incident.incidents
    ADD COLUMN application_name varchar(100),
    ADD COLUMN service_name     varchar(100),
    ADD COLUMN environment      varchar(50);

-- 2. The details shown for an incident's events are stored with the link.
ALTER TABLE incident.incident_events
    ADD COLUMN event_timestamp timestamptz,
    ADD COLUMN severity        varchar(10),
    ADD COLUMN message         text,
    ADD COLUMN exception_type  varchar(255),
    ADD COLUMN host            varchar(255),
    ADD COLUMN trace_id        varchar(128),
    ADD COLUMN correlation_id  varchar(128);

-- 3. Copy the details of existing incidents (created in Phase 2) from the ingestion schema.
--    The check keeps this migration usable on a database without that schema.
DO $$
BEGIN
    IF to_regclass('ingestion.error_events') IS NOT NULL THEN
        UPDATE incident.incidents i
        SET application_name = a.name,
            service_name = a.service_name,
            environment = a.environment
        FROM ingestion.applications a
        WHERE a.id = i.application_id;

        UPDATE incident.incident_events ie
        SET event_timestamp = e.event_timestamp,
            severity = e.severity,
            message = e.message,
            exception_type = e.exception_type,
            host = e.host,
            trace_id = e.trace_id,
            correlation_id = e.correlation_id
        FROM ingestion.error_events e
        WHERE e.id = ie.error_event_id;
    END IF;
END $$;

ALTER TABLE incident.incidents
    ALTER COLUMN application_name SET NOT NULL,
    ALTER COLUMN service_name SET NOT NULL,
    ALTER COLUMN environment SET NOT NULL;

ALTER TABLE incident.incident_events
    ALTER COLUMN event_timestamp SET NOT NULL,
    ALTER COLUMN severity SET NOT NULL,
    ALTER COLUMN message SET NOT NULL,
    ADD CONSTRAINT ck_incident_events_severity CHECK (severity IN ('DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'));

-- 4. No foreign keys into another service's schema. application_id and error_event_id remain
--    as plain identifiers of the ingestion-side records.
ALTER TABLE incident.incidents DROP CONSTRAINT incidents_application_id_fkey;
ALTER TABLE incident.incident_events DROP CONSTRAINT incident_events_error_event_id_fkey;

-- 5. An event belongs to at most one incident, so the event ID alone identifies the link.
ALTER TABLE incident.incident_events DROP CONSTRAINT incident_events_pkey;
ALTER TABLE incident.incident_events DROP CONSTRAINT uq_incident_events_error_event;
ALTER TABLE incident.incident_events ADD CONSTRAINT incident_events_pkey PRIMARY KEY (error_event_id);
CREATE INDEX ix_incident_events_incident_timestamp
    ON incident.incident_events (incident_id, event_timestamp DESC);

-- 6. Kafka messages that were already processed. Kafka delivers at least once; a message whose
--    event ID is listed here is skipped, so redelivery cannot count an occurrence twice.
CREATE TABLE incident.processed_events (
    event_id     uuid        PRIMARY KEY,
    outcome      varchar(30) NOT NULL,
    incident_id  uuid,
    processed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_processed_events_outcome
        CHECK (outcome IN ('INCIDENT_CREATED', 'INCIDENT_UPDATED', 'IGNORED_SEVERITY', 'ALREADY_ASSIGNED'))
);
