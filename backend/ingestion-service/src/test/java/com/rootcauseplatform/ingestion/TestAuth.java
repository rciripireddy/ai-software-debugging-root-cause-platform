package com.rootcauseplatform.ingestion;

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
 * Credentials for the tests, matching src/test/resources/config/application.yml. Access tokens
 * are signed the way incident-service signs them.
 */
public final class TestAuth {

    public static final String JWT_SECRET = "test-only-jwt-secret-for-automated-tests-0001";
    public static final String SERVICE_TOKEN = "test-only-internal-service-token-000000001";
    public static final String API_KEY = "test-only-ingestion-api-key-0000000000001";
    public static final String ISSUER = "root-cause-platform";
    public static final String AUDIENCE = "root-cause-platform-api";

    private TestAuth() {
    }

    /** A client that reads as a VIEWER and submits events with the ingestion API key. */
    public static ApiClient client(int port) {
        return new ApiClient(port).withCredentials(bearer(token("VIEWER")), apiKey());
    }

    public static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    public static Map<String, String> apiKey() {
        return Map.of("X-Api-Key", API_KEY);
    }

    public static Map<String, String> serviceToken() {
        return Map.of("X-Service-Token", SERVICE_TOKEN);
    }

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
