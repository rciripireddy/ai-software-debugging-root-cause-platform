package com.rootcauseplatform.incident.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.rootcauseplatform.incident.auth.LoginAttemptLimiter;
import com.rootcauseplatform.incident.service.RateLimitExceededException;

class FixedWindowRateLimiterTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));

    @Test
    void allowsUpToTheLimitPerWindowAndKey() {
        var limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1), clock);

        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire("a")).isEqualTo(Duration.ofSeconds(40));
        assertThat(limiter.tryAcquire("b")).as("other keys are counted separately").isZero();

        clock.advance(Duration.ofSeconds(40));
        assertThat(limiter.tryAcquire("a")).as("a new window starts").isZero();
    }

    @Test
    void resetForgetsAKey() {
        var limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(5), clock);
        limiter.record("a");
        assertThat(limiter.retryAfter("a")).isPositive();

        limiter.reset("a");

        assertThat(limiter.retryAfter("a")).isZero();
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginLimiterBlocksAClientThatFailsAcrossManyUsernames() {
        var properties = new AuthProperties(null, null, null, null, null, false, null,
                new AuthProperties.LoginLimit(5, 3, Duration.ofMinutes(15)), null);
        var limiter = new LoginAttemptLimiter(properties, clock);

        limiter.recordFailure("alice", "10.0.0.1");
        limiter.recordFailure("bob", "10.0.0.1");
        limiter.recordFailure("carol", "10.0.0.1");

        assertThatThrownBy(() -> limiter.checkAllowed("dave", "10.0.0.1"))
                .isInstanceOf(RateLimitExceededException.class);
        limiter.checkAllowed("dave", "10.0.0.2"); // another client is not affected
        clock.advance(Duration.ofMinutes(15));
        limiter.checkAllowed("dave", "10.0.0.1");
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        assertThat(new RateLimitExceededException("x", Duration.ofMillis(1500)).retryAfterSeconds()).isEqualTo(2);
        assertThat(new RateLimitExceededException("x", Duration.ofMillis(10)).retryAfterSeconds()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
