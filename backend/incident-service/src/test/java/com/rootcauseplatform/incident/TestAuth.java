package com.rootcauseplatform.incident;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Credentials for the tests, matching src/test/resources/config/application.yml. Tokens are
 * signed like incident-service's own, so the API tests do not depend on the sign-in endpoint.
 */
public final class TestAuth {

    public static final String JWT_SECRET = "test-only-jwt-secret-for-automated-tests-0001";
    public static final String SERVICE_TOKEN = "test-only-internal-service-token-000000001";
    public static final String ISSUER = "root-cause-platform";
    public static final String AUDIENCE = "root-cause-platform-api";
    public static final String ADMIN_USERNAME = "test-admin";
    public static final String ADMIN_PASSWORD = "test-only-admin-password-1";
    public static final String VIEWER_USERNAME = "test-viewer";
    public static final String VIEWER_PASSWORD = "test-only-viewer-password-1";

    private TestAuth() {
    }

    /** A client that reads as a VIEWER and writes as an ADMIN. */
    public static ApiClient client(int port) {
        return new ApiClient(port).withCredentials(bearer(token("VIEWER")), bearer(token("ADMIN")));
    }

    public static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    public static Map<String, String> serviceToken() {
        return Map.of("X-Service-Token", SERVICE_TOKEN);
    }

    /** A valid token for a user that is not in the database (enough for the incident API). */
    public static String token(String role) {
        Instant now = Instant.now();
        return token(claims(role, now, now.plus(10, ChronoUnit.MINUTES)), JWT_SECRET);
    }

    public static JwtClaimsSet.Builder claims(String role, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("username", "token-user")
                .claim("role", role)
                .claim("csrf", "test-csrf-token");
    }

    public static String token(JwtClaimsSet.Builder claims, String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
