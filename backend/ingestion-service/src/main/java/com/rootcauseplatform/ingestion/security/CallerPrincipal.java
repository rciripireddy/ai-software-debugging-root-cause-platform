package com.rootcauseplatform.ingestion.security;

import java.time.Instant;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Who made a request: a signed-in user (from an access token), another service of the platform
 * (internal service token) or a monitored application submitting events (ingestion API key).
 *
 * @param csrfToken  the token's CSRF value (users only)
 * @param fromCookie the access token came from the session cookie, so state-changing requests
 *                   must also carry the CSRF token
 */
public record CallerPrincipal(Kind kind, String id, String username, String role, String csrfToken,
        Instant expiresAt, boolean fromCookie) {

    public enum Kind {
        USER,
        SERVICE,
        LOG_PRODUCER
    }

    public static final String SERVICE_ROLE = "SERVICE";
    public static final String LOG_PRODUCER_ROLE = "LOG_PRODUCER";

    static CallerPrincipal user(TokenVerifier.VerifiedToken token, boolean fromCookie) {
        return new CallerPrincipal(Kind.USER, token.subject(), token.username(), token.role(), token.csrfToken(),
                token.expiresAt(), fromCookie);
    }

    static CallerPrincipal service() {
        return new CallerPrincipal(Kind.SERVICE, "internal-service", null, SERVICE_ROLE, null, null, false);
    }

    static CallerPrincipal logProducer() {
        return new CallerPrincipal(Kind.LOG_PRODUCER, "log-producer", null, LOG_PRODUCER_ROLE, null, null, false);
    }

    List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** Never includes the CSRF token. */
    @Override
    public String toString() {
        return "CallerPrincipal[kind=" + kind + ", id=" + id + ", role=" + role + "]";
    }
}
