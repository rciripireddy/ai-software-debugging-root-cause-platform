package com.rootcauseplatform.incident.auth;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.rootcauseplatform.incident.security.AuthProperties;
import com.rootcauseplatform.incident.security.FixedWindowRateLimiter;
import com.rootcauseplatform.incident.service.RateLimitExceededException;

/**
 * Limits failed sign-ins per username (password guessing against one account) and per client
 * address (guessing across accounts). While either limit is reached, sign-in is refused without
 * checking the password. In memory, per instance (see {@link FixedWindowRateLimiter}).
 */
@Component
public class LoginAttemptLimiter {

    private final FixedWindowRateLimiter perUser;
    private final FixedWindowRateLimiter perClient;

    public LoginAttemptLimiter(AuthProperties properties, Clock clock) {
        AuthProperties.LoginLimit limit = properties.loginLimit();
        this.perUser = new FixedWindowRateLimiter(limit.maxFailuresPerUser(), limit.window(), clock);
        this.perClient = new FixedWindowRateLimiter(limit.maxFailuresPerClient(), limit.window(), clock);
    }

    /** @throws RateLimitExceededException if the username or the client has too many recent failures */
    public void checkAllowed(String username, String client) {
        Duration wait = max(perUser.retryAfter(username), perClient.retryAfter(client));
        if (!wait.isZero()) {
            throw new RateLimitExceededException("Too many failed sign-in attempts. Try again later.", wait);
        }
    }

    public void recordFailure(String username, String client) {
        perUser.record(username);
        perClient.record(client);
    }

    public void recordSuccess(String username) {
        perUser.reset(username);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
