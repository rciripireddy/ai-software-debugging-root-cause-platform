package com.rootcauseplatform.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.ServletWebRequest;

/** Covers failures that are impractical to trigger through the HTTP API in tests. */
class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/logs"));

    @Test
    void databaseOutagesReturnServiceUnavailableWithoutDetails() {
        ResponseEntity<Object> outage = handler.handleDatabaseUnavailable(
                new CannotCreateTransactionException("Could not open JPA EntityManager",
                        new IllegalStateException("Connection to postgres:5432 refused")), request);
        ResponseEntity<Object> resourceFailure = handler.handleDatabaseUnavailable(
                new DataAccessResourceFailureException("I/O error on socket"), request);

        for (ResponseEntity<Object> response : List.of(outage, resourceFailure)) {
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            ProblemDetail problem = (ProblemDetail) response.getBody();
            assertThat(problem.getTitle()).isEqualTo("Service unavailable");
            assertThat(problem.getDetail()).doesNotContain("JPA", "postgres", "socket");
        }
    }

    @Test
    void unexpectedErrorsReturnAGenericMessage() {
        ResponseEntity<Object> response = handler.handleUnexpected(
                new IllegalStateException("internal detail that must not leak"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getDetail()).doesNotContain("internal detail");
    }
}
