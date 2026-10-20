package com.rootcauseplatform.ingestion.api;

import java.time.Instant;
import java.util.UUID;

public record LogEventCreatedResponse(UUID id, UUID applicationId, String fingerprint, Instant receivedAt) {
}
