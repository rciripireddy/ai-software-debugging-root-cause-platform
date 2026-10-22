package com.rootcauseplatform.ingestion.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless security for the ingestion API: every request is authenticated from its own
 * credential ({@link RequestAuthenticationFilter}), no HTTP session is created, and anything not
 * listed below is denied.
 *
 * <table>
 * <tr><th>Endpoint</th><th>Allowed for</th></tr>
 * <tr><td>{@code /actuator/health/**}, {@code /actuator/info}</td><td>everyone</td></tr>
 * <tr><td>{@code POST /api/v1/logs}</td><td>log producers (ingestion API key)</td></tr>
 * <tr><td>{@code GET /api/v1/logs/**}, {@code GET /api/v1/applications}</td>
 * <td>VIEWER, ADMIN, internal services</td></tr>
 * </table>
 */
@Configuration
public class SecurityConfiguration {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TokenVerifier tokenVerifier(AuthProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(requireSecret(properties.jwtSecret(), "AUTH_JWT_SECRET"), "HmacSHA256");
        return new TokenVerifier(key, properties.issuer(), properties.audience(), clock);
    }

    @Bean
    SecurityProblems securityProblems(JsonMapper jsonMapper) {
        return new SecurityProblems(jsonMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenVerifier tokenVerifier,
            AuthProperties properties, SecurityProblems problems) throws Exception {
        var authenticationFilter = new RequestAuthenticationFilter(tokenVerifier, properties.cookieName(),
                secretString(properties.serviceToken(), "INTERNAL_SERVICE_TOKEN"),
                secretString(properties.ingestionApiKey(), "INGESTION_API_KEY"), Set.of(), problems);

        http
                // CSRF tokens are checked by RequestAuthenticationFilter for cookie-authenticated requests.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny())
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .addFilterBefore(authenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/logs").hasRole(CallerPrincipal.LOG_PRODUCER_ROLE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/logs", "/api/v1/logs/**", "/api/v1/applications")
                        .hasAnyRole("ADMIN", "VIEWER", CallerPrincipal.SERVICE_ROLE)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    /** Fails startup with a message that names the variable but never shows its value. */
    static byte[] requireSecret(String value, String variable) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(variable + " must be set to a random value of at least "
                    + MIN_SECRET_BYTES + " characters (see .env.example)");
        }
        return bytes;
    }

    private static String secretString(String value, String variable) {
        return new String(requireSecret(value, variable), StandardCharsets.UTF_8);
    }
}
