package com.rootcauseplatform.incident.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.rootcauseplatform.incident.security.AuthProperties;

/**
 * Issues signed access tokens (JWT, HS256). Claims: {@code sub} (user ID), {@code username},
 * {@code role}, {@code csrf} (a random value the dashboard must echo on state-changing requests),
 * plus issuer, audience, issue/expiry time and a unique ID. No password data or personal details.
 */
@Component
public class TokenService {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(SecretKey jwtSigningKey, AuthProperties properties, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(UserAccount user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.tokenTtl());
        String csrfToken = randomToken();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .claim("csrf", csrfToken)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, csrfToken, expiresAt);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@link #toString()} leaves out the token and CSRF value. */
    public record IssuedToken(String value, String csrfToken, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }
}
