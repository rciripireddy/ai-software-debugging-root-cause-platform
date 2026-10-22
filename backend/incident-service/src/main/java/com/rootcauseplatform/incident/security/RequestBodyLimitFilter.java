package com.rootcauseplatform.incident.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects request bodies larger than {@code app.http.max-request-body} (413) before they are
 * read, and bodies without a declared length (411), whose size could only be checked while
 * reading them. Every JSON client sends {@code Content-Length} for these small bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final SecurityProblems problems;

    public RequestBodyLimitFilter(@Value("${app.http.max-request-body:1MB}") DataSize maxRequestBody,
            SecurityProblems problems) {
        this.maxBytes = maxRequestBody.toBytes();
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            problems.write(request, response, HttpStatus.LENGTH_REQUIRED, "Length required",
                    "Send the request body with a Content-Length header.");
            return;
        }
        if (request.getContentLengthLong() > maxBytes) {
            problems.write(request, response, HttpStatus.CONTENT_TOO_LARGE, "Request too large",
                    "The request body must not be larger than " + maxBytes / 1024 + " KB.");
            return;
        }
        chain.doFilter(request, response);
    }
}
