-- Transactional outbox: messages to publish to Kafka. A row is written in the same
-- transaction as the error event it describes, so a stored event can never miss its
-- message, even if Kafka is unavailable. A background publisher sends pending rows and
-- sets published_at once Kafka has acknowledged the message.
CREATE TABLE ingestion.outbox_events (
    id                     uuid         PRIMARY KEY,
    aggregate_id           uuid         NOT NULL,
    event_type             varchar(100) NOT NULL,
    topic                  varchar(255) NOT NULL,
    message_key            varchar(255) NOT NULL,
    payload                jsonb        NOT NULL,
    request_correlation_id varchar(128),
    created_at             timestamptz  NOT NULL DEFAULT now(),
    published_at           timestamptz,
    attempt_count          integer      NOT NULL DEFAULT 0,
    last_attempt_at        timestamptz,
    next_attempt_at        timestamptz  NOT NULL DEFAULT now(),
    last_error             varchar(500),
    CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0)
);

-- The publisher polls for unpublished rows that are due; published rows are not indexed.
CREATE INDEX ix_outbox_events_pending
    ON ingestion.outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;

-- Finding the message for an error event, e.g. when investigating delivery.
CREATE INDEX ix_outbox_events_aggregate_id ON ingestion.outbox_events (aggregate_id);
