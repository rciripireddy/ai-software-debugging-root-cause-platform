package com.rootcauseplatform.incident.service;

import java.time.Duration;

/** Too many requests of one kind from one caller; answered with 429 and {@code Retry-After}. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, at least 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
