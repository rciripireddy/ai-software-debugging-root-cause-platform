package com.rootcauseplatform.incident.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.rootcauseplatform.incident.logging.CorrelationIdFilter;
import com.rootcauseplatform.incident.security.TokenVerifier.InvalidTokenException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 responses of the security filters, as RFC 9457 problem details in the same shape
 * as {@code GlobalExceptionHandler}, with one structured log line each.
 */
public class SecurityProblems implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblems.class);

    private final JsonMapper jsonMapper;

    public SecurityProblems(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** No valid credential: missing, malformed, wrongly signed or expired. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        Object failure = request.getAttribute(RequestAuthenticationFilter.FAILURE_ATTRIBUTE);
        boolean expired = failure == InvalidTokenException.Reason.EXPIRED;
        log.atInfo()
                .setMessage("Request not authenticated")
                .addKeyValue("action", "auth.unauthenticated")
                .addKeyValue("reason", failure == null ? "missing_credentials" : failure.toString().toLowerCase())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .log();
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                failure == null ? "Bearer" : "Bearer error=\"invalid_token\"");
        if (expired) {
            write(request, response, HttpStatus.UNAUTHORIZED, "Session expired", "Your session has expired. Sign in again.");
        } else {
            write(request, response, HttpStatus.UNAUTHORIZED, "Authentication required",
                    "Sign in or provide valid credentials to access this resource.");
        }
    }

    /** Authenticated, but the role does not allow the request. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var caller = authentication != null && authentication.getPrincipal() instanceof CallerPrincipal principal
                ? principal : null;
        var event = log.atWarn()
                .setMessage("Access denied")
                .addKeyValue("action", "auth.forbidden")
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI());
        if (caller != null) {
            event = event.addKeyValue("principalKind", caller.kind())
                    .addKeyValue("principalId", caller.id())
                    .addKeyValue("role", caller.role());
        }
        event.log();
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                "Your role does not allow this action.");
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title,
            String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.put("correlationId", correlationId);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
