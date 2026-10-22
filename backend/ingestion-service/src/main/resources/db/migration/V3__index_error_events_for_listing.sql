-- Phase 5: the dashboard lists events newest first across all applications and counts recently
-- received events.

-- GET /api/v1/logs: newest event timestamp first (the existing index is per application).
CREATE INDEX ix_error_events_event_timestamp
    ON ingestion.error_events (event_timestamp DESC, id DESC);

-- GET /api/v1/logs/activity: events received within the last hours.
CREATE INDEX ix_error_events_created_at ON ingestion.error_events (created_at);
