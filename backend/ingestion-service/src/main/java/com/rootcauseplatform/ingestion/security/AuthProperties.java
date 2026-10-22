package com.rootcauseplatform.ingestion.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings ({@code app.auth.*}, see docs/configuration.md). The secrets come from
 * environment variables; they are checked at startup by {@link SecurityConfiguration} and never
 * logged ({@link #toString()} leaves them out).
 *
 * @param jwtSecret       HS256 key that verifies access tokens issued by incident-service
 * @param issuer          required {@code iss} of access tokens
 * @param audience        required {@code aud} of access tokens
 * @param cookieName      name of the session cookie that carries the token for the dashboard
 * @param serviceToken    internal credential that analysis-service sends in {@code X-Service-Token}
 * @param ingestionApiKey key that log producers send in {@code X-Api-Key} to submit events
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        String audience,
        String cookieName,
        String serviceToken,
        String ingestionApiKey) {

    @Override
    public String toString() {
        return "AuthProperties[issuer=" + issuer + ", audience=" + audience + ", cookieName=" + cookieName + "]";
    }
}
