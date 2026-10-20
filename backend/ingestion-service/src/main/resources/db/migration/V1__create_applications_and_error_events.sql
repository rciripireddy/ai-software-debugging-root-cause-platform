-- Monitored applications. An application is identified by its name, the service that
-- reports events and the environment it runs in, e.g. ("demo-shop", "payment-service", "staging").
CREATE TABLE ingestion.applications (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         varchar(100) NOT NULL,
    service_name varchar(100) NOT NULL,
    environment  varchar(50)  NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_applications_name_service_environment UNIQUE (name, service_name, environment)
);

-- Individual log/error events as reported by monitored applications.
CREATE TABLE ingestion.error_events (
    id              uuid         PRIMARY KEY,
    application_id  uuid         NOT NULL REFERENCES ingestion.applications (id),
    event_timestamp timestamptz  NOT NULL,
    severity        varchar(10)  NOT NULL,
    message         text         NOT NULL,
    exception_type  varchar(255),
    stack_trace     text,
    source          varchar(255),
    host            varchar(255),
    trace_id        varchar(128),
    correlation_id  varchar(128),
    fingerprint     varchar(64)  NOT NULL,
    raw_payload     jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_error_events_severity CHECK (severity IN ('DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL')),
    CONSTRAINT ck_error_events_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{64}$')
);

-- Recent events per application (dashboard and incident views).
CREATE INDEX ix_error_events_application_timestamp
    ON ingestion.error_events (application_id, event_timestamp DESC);

-- Finding all occurrences of the same error.
CREATE INDEX ix_error_events_fingerprint ON ingestion.error_events (fingerprint);

-- Following a request across services. Most events have no trace or correlation ID.
CREATE INDEX ix_error_events_trace_id
    ON ingestion.error_events (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX ix_error_events_correlation_id
    ON ingestion.error_events (correlation_id) WHERE correlation_id IS NOT NULL;
