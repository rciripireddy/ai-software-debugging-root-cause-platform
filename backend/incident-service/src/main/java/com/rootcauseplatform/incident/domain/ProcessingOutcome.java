package com.rootcauseplatform.incident.domain;

/** What happened to an {@code ErrorEventCreated} message. */
public enum ProcessingOutcome {
    /** The event opened a new incident. */
    INCIDENT_CREATED,
    /** The event was added to an existing unresolved incident. */
    INCIDENT_UPDATED,
    /** The event's severity is below the configured minimum; no incident is involved. */
    IGNORED_SEVERITY,
    /** The event was already added to an incident through the deprecated manual API. */
    ALREADY_ASSIGNED,
    /** The message was processed before; nothing changed. Not stored. */
    DUPLICATE
}
