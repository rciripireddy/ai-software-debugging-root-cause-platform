-- Stored incident analyses. Every analysis request adds a new revision; earlier revisions are
-- kept as history. incident_id refers to incident-service's incident, deliberately without a
-- foreign key: the analysis schema does not depend on another service's tables.
CREATE TABLE analysis.incident_analyses (
    id                     uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id            uuid         NOT NULL,
    revision               integer      NOT NULL,
    analysis_version       varchar(20)  NOT NULL,
    generated_at           timestamptz  NOT NULL,
    summary                text         NOT NULL,
    confidence             varchar(10)  NOT NULL,
    primary_category       varchar(40),
    primary_title          varchar(500),
    events_analyzed        integer      NOT NULL,
    related_incident_count integer      NOT NULL,
    -- The complete result (candidates, evidence, patterns, related incidents) as returned by the API.
    result                 jsonb        NOT NULL,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_incident_analyses_incident_revision UNIQUE (incident_id, revision),
    CONSTRAINT ck_incident_analyses_revision CHECK (revision > 0),
    CONSTRAINT ck_incident_analyses_confidence CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- The unique constraint's index also serves "latest revision of an incident" and the history.

-- Finding analyses by suggested category, e.g. all incidents with a likely database problem.
CREATE INDEX ix_incident_analyses_primary_category
    ON analysis.incident_analyses (primary_category, generated_at DESC);
