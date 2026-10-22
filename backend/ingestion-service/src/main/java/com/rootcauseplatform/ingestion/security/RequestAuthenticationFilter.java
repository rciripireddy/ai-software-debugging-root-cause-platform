package com.rootcauseplatform.ingestion.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.rootcauseplatform.ingestion.security.TokenVerifier.InvalidTokenException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates a request from the first credential it carries:
 * <ol>
 * <li>{@code X-Service-Token}: the internal service credential (analysis-service);</li>
 * <li>{@code X-Api-Key}: the ingestion API key of monitored applications (submitting events);</li>
 * <li>{@code Authorization: Bearer <access token>}: for API clients and scripts;</li>
 * <li>the session cookie set at sign-in: the same access token, for the dashboard.</li>
 * </ol>
 * A request with a missing or invalid credential continues unauthenticated, so public endpoints
 * keep working; protected endpoints then answer 401 with the reason kept here. A state-changing
 * request authenticated by the cookie must also send the session's CSRF token in
 * {@code X-CSRF-Token}, otherwise it is rejected with 403.
 *
 * <p>Created by {@link SecurityConfiguration} rather than as a bean, so it only runs inside the
 * security filter chain.
 */
public class RequestAuthenticationFilter extends OncePerRequestFilter {

    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    static final String FAILURE_ATTRIBUTE = RequestAuthenticationFilter.class.getName() + ".failure";

    private static final Logger log = LoggerFactory.getLogger(RequestAuthenticationFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String BEARER_PREFIX = "bearer ";

    private final TokenVerifier tokenVerifier;
    private final String cookieName;
    private final byte[] serviceToken;
    private final byte[] ingestionApiKey;
    private final Set<String> csrfExemptPaths;
    private final SecurityProblems problems;

    public RequestAuthenticationFilter(TokenVerifier tokenVerifier, String cookieName, String serviceToken,
            String ingestionApiKey, Set<String> csrfExemptPaths, SecurityProblems problems) {
        this.tokenVerifier = tokenVerifier;
        this.cookieName = cookieName;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
        this.ingestionApiKey = ingestionApiKey.getBytes(StandardCharsets.UTF_8);
        this.csrfExemptPaths = csrfExemptPaths;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CallerPrincipal caller = null;
        try {
            caller = authenticate(request);
        } catch (InvalidTokenException ex) {
            request.setAttribute(FAILURE_ATTRIBUTE, ex.reason());
        }

        if (caller != null) {
            if (caller.fromCookie() && !SAFE_METHODS.contains(request.getMethod())
                    && !csrfExemptPaths.contains(request.getRequestURI()) && !csrfTokenMatches(request, caller)) {
                log.atWarn()
                        .setMessage("Request rejected: missing or invalid CSRF token")
                        .addKeyValue("action", "auth.csrf_rejected")
                        .addKeyValue("principalId", caller.id())
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .log();
                problems.write(request, response, HttpStatus.FORBIDDEN, "CSRF token missing or invalid",
                        "Reload the dashboard and try again.");
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null,
                    caller.authorities()));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private CallerPrincipal authenticate(HttpServletRequest request) {
        String presentedServiceToken = request.getHeader(SERVICE_TOKEN_HEADER);
        if (presentedServiceToken != null) {
            if (constantTimeEquals(presentedServiceToken, serviceToken)) {
                return CallerPrincipal.service();
            }
            log.atWarn()
                    .setMessage("Invalid service token")
                    .addKeyValue("action", "auth.service_token_rejected")
                    .addKeyValue("path", request.getRequestURI())
                    .log();
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID);
        }

        String presentedApiKey = request.getHeader(API_KEY_HEADER);
        if (presentedApiKey != null) {
            if (constantTimeEquals(presentedApiKey, ingestionApiKey)) {
                return CallerPrincipal.logProducer();
            }
            log.atWarn()
                    .setMessage("Invalid ingestion API key")
                    .addKeyValue("action", "auth.api_key_rejected")
                    .addKeyValue("path", request.getRequestURI())
                    .log();
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID);
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            boolean bearer = authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
            String token = bearer ? authorization.substring(BEARER_PREFIX.length()).trim() : "";
            if (token.isEmpty()) {
                throw new InvalidTokenException(InvalidTokenException.Reason.INVALID);
            }
            return CallerPrincipal.user(tokenVerifier.verify(token), false);
        }

        Cookie cookie = WebUtils.getCookie(request, cookieName);
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
            return CallerPrincipal.user(tokenVerifier.verify(cookie.getValue()), true);
        }
        return null;
    }

    private static boolean csrfTokenMatches(HttpServletRequest request, CallerPrincipal caller) {
        String presented = request.getHeader(CSRF_HEADER);
        return presented != null && constantTimeEquals(presented, caller.csrfToken().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String presented, byte[] expected) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
    }
}
