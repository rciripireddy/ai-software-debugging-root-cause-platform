package com.rootcauseplatform.ingestion.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts events per key (a username, a client address, ...) in fixed time windows. Kept in
 * memory, so each service instance counts on its own and a restart resets the counts; enough to
 * slow down guessing and accidental floods on a single-instance deployment.
 */
public final class FixedWindowRateLimiter {

    /** Above this many keys, expired windows are removed. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit must be positive and window longer than zero");
        }
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Counts one event for {@code key} unless the key has reached the limit.
     *
     * @return {@link Duration#ZERO} if the event was allowed, otherwise the time until it will be
     */
    public Duration tryAcquire(String key) {
        Duration wait = retryAfter(key);
        if (wait.isZero()) {
            record(key);
        }
        return wait;
    }

    /** The time until {@code key} is below the limit again, without counting an event. */
    public Duration retryAfter(String key) {
        Instant now = clock.instant();
        Window current = windows.get(key);
        if (current == null || current.endsAt(window).compareTo(now) <= 0 || current.count() < limit) {
            return Duration.ZERO;
        }
        return Duration.between(now, current.endsAt(window));
    }

    public void record(String key) {
        Instant now = clock.instant();
        windows.compute(key, (k, current) -> current == null || current.endsAt(window).compareTo(now) <= 0
                ? new Window(now, 1)
                : new Window(current.start(), current.count() + 1));
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(entry -> entry.endsAt(window).compareTo(now) <= 0);
        }
    }

    public void reset(String key) {
        windows.remove(key);
    }

    private record Window(Instant start, int count) {

        Instant endsAt(Duration length) {
            return start.plus(length);
        }
    }
}
