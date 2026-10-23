package com.rootcauseplatform.incident.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import com.rootcauseplatform.incident.auth.AuthController;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless security for the incident API: every request is authenticated from its own
 * credential ({@link RequestAuthenticationFilter}), no HTTP session is created, and anything not
 * listed below is denied.
 *
 * <table>
 * <tr><th>Endpoint</th><th>Allowed for</th></tr>
 * <tr><td>{@code /actuator/health/**}, {@code /actuator/info}</td><td>everyone</td></tr>
 * <tr><td>{@code POST /api/v1/auth/login}, {@code POST /api/v1/auth/logout}</td><td>everyone</td></tr>
 * <tr><td>other {@code /api/v1/auth/**}</td><td>signed-in users</td></tr>
 * <tr><td>{@code GET /api/v1/incidents/**}</td><td>VIEWER, ADMIN, internal services</td></tr>
 * <tr><td>{@code POST /api/v1/incidents} (deprecated)</td><td>ADMIN</td></tr>
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
    SecretKey jwtSigningKey(AuthProperties properties) {
        return new SecretKeySpec(requireSecret(properties.jwtSecret(), "AUTH_JWT_SECRET"), "HmacSHA256");
    }

    @Bean
    TokenVerifier tokenVerifier(SecretKey jwtSigningKey, AuthProperties properties, Clock clock) {
        return new TokenVerifier(jwtSigningKey, properties.issuer(), properties.audience(), clock);
    }

    @Bean
    SecurityProblems securityProblems(JsonMapper jsonMapper) {
        return new SecurityProblems(jsonMapper);
    }

    /** BCrypt (cost 12), with the algorithm stored as a prefix so it can be changed later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenVerifier tokenVerifier,
            AuthProperties properties, SecurityProblems problems) throws Exception {
        String serviceToken = new String(requireSecret(properties.serviceToken(), "INTERNAL_SERVICE_TOKEN"),
                StandardCharsets.UTF_8);
        // Sign-in has no session yet; its JSON-only body cannot be sent by a cross-site form.
        var authenticationFilter = new RequestAuthenticationFilter(tokenVerifier, properties.cookieName(),
                serviceToken, Set.of(AuthController.LOGIN_PATH), problems);

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
                        // Scraped by Prometheus on the internal network; the dashboard proxy does
                        // not route it and production-style Compose does not publish the port.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, AuthController.LOGIN_PATH, AuthController.LOGOUT_PATH)
                        .permitAll()
                        .requestMatchers("/api/v1/auth/**").hasAnyRole("ADMIN", "VIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents", "/api/v1/incidents/**")
                        .hasAnyRole("ADMIN", "VIEWER", CallerPrincipal.SERVICE_ROLE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents").hasRole("ADMIN")
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
}
