package com.rootcauseplatform.incident.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings ({@code app.auth.*}, see docs/configuration.md). The secrets come from
 * environment variables; they are checked at startup by {@link SecurityConfiguration} and never
 * logged ({@link #toString()} leaves them out).
 *
 * @param jwtSecret     HS256 key that signs and verifies access tokens (at least 32 bytes)
 * @param issuer        {@code iss} of issued tokens; other values are rejected
 * @param audience      {@code aud} of issued tokens; tokens without it are rejected
 * @param tokenTtl      lifetime of an access token
 * @param cookieName    name of the HttpOnly session cookie that carries the token for the dashboard
 * @param cookieSecure  mark the cookie {@code Secure}; required when the dashboard is served over HTTPS
 * @param serviceToken  internal credential that analysis-service sends in {@code X-Service-Token}
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        String audience,
        Duration tokenTtl,
        String cookieName,
        boolean cookieSecure,
        String serviceToken,
        LoginLimit loginLimit,
        Bootstrap bootstrap) {

    /** Failed sign-ins allowed per username and per client address within {@code window}. */
    public record LoginLimit(int maxFailuresPerUser, int maxFailuresPerClient, Duration window) {
    }

    /** Accounts created at startup when they do not exist yet (local development). */
    public record Bootstrap(Account admin, Account viewer) {
    }

    public record Account(String username, String password, String displayName) {

        public boolean configured() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "Account[username=" + username + ", displayName=" + displayName + "]";
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[issuer=" + issuer + ", audience=" + audience + ", tokenTtl=" + tokenTtl
                + ", cookieName=" + cookieName + ", cookieSecure=" + cookieSecure + ", loginLimit=" + loginLimit
                + ", bootstrap=" + bootstrap + "]";
    }
}
