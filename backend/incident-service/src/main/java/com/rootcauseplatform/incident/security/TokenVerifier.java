package com.rootcauseplatform.incident.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies access tokens: HS256 signature, issuer, audience, expiry and the claims the platform
 * relies on. Tokens are issued by incident-service ({@code TokenService}); the same checks run in
 * every service.
 */
public class TokenVerifier {

    /** Tolerated clock difference between services. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Set<String> ROLES = Set.of("ADMIN", "VIEWER");

    private final NimbusJwtDecoder decoder;
    private final Clock clock;

    public TokenVerifier(SecretKey key, String issuer, String audience, Clock clock) {
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Expiry is checked below, so an expired token can be told apart from an invalid one.
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
        this.clock = clock;
    }

    public VerifiedToken verify(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException ex) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID);
        }
        Instant now = clock.instant();
        Instant expiresAt = jwt.getExpiresAt();
        Instant issuedAt = jwt.getIssuedAt();
        if (expiresAt == null || issuedAt == null || issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID);
        }
        if (now.isAfter(expiresAt.plus(CLOCK_SKEW))) {
            throw new InvalidTokenException(InvalidTokenException.Reason.EXPIRED);
        }
        String subject = jwt.getSubject();
        String username = jwt.getClaimAsString("username");
        String role = jwt.getClaimAsString("role");
        String csrf = jwt.getClaimAsString("csrf");
        if (isBlank(subject) || isBlank(username) || isBlank(csrf) || !ROLES.contains(role)) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID);
        }
        return new VerifiedToken(subject, username, role, csrf, expiresAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The claims of a valid access token. */
    public record VerifiedToken(String subject, String username, String role, String csrfToken, Instant expiresAt) {

        @Override
        public String toString() {
            return "VerifiedToken[subject=" + subject + ", role=" + role + ", expiresAt=" + expiresAt + "]";
        }
    }

    /** The token is malformed, not signed with the platform key, for another issuer/audience, or expired. */
    public static class InvalidTokenException extends RuntimeException {

        public enum Reason {
            INVALID,
            EXPIRED
        }

        private final Reason reason;

        public InvalidTokenException(Reason reason) {
            super("Access token " + reason.name().toLowerCase());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
