package com.rootcauseplatform.ingestion.api;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.rootcauseplatform.ingestion.logging.CorrelationIdFilter;
import com.rootcauseplatform.ingestion.service.ResourceNotFoundException;

import tools.jackson.core.JacksonException;

/**
 * Translates exceptions into RFC 9457 problem details. Responses never contain stack traces
 * or exception class names; unexpected errors are logged with their stack trace instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage()), request);
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<Object> handleDatabaseUnavailable(Exception ex, WebRequest request) {
        log.atWarn().setMessage("Database unavailable")
                .addKeyValue("action", "database.unavailable")
                .addKeyValue("path", path(request))
                .addKeyValue("reason", ex.getClass().getSimpleName())
                .log();
        return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "The service is temporarily unable to process requests. Try again later."), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.atError().setCause(ex).setMessage("Unhandled exception while processing request")
                .addKeyValue("path", path(request)).log();
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred. Quote the correlation ID when reporting this problem."), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldError)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "The request contains invalid fields.");
        problem.setProperty("errors", errors);
        return respond(problem, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "The request contains invalid parameters.");
        problem.setProperty("errors", errors);
        return respond(problem, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = "The request body is missing or is not valid JSON.";
        if (ex.getCause() instanceof JacksonException jacksonException && !jacksonException.getPath().isEmpty()) {
            String field = jacksonException.getPath().getLast().getPropertyName();
            if (field != null) {
                detail = "The value of field '" + field + "' is invalid.";
            }
        }
        return respond(problem(HttpStatus.BAD_REQUEST, "Malformed request", detail), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = "Parameter '" + ex.getPropertyName() + "' has an invalid value.";
        return respond(problem(HttpStatus.BAD_REQUEST, "Invalid parameter", detail), request);
    }

    /** Adds the correlation ID to problem details produced by the Spring MVC base class. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            addCorrelationId(problem);
            logRejected(problem, request);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(ProblemDetail problem, WebRequest request) {
        if (problem.getStatus() < 500) {
            logRejected(problem, request);
        }
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        addCorrelationId(problem);
        return problem;
    }

    private static void addCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()));
    }

    private static void logRejected(ProblemDetail problem, WebRequest request) {
        log.atInfo()
                .setMessage("Request rejected")
                .addKeyValue("action", "request.reject")
                .addKeyValue("status", problem.getStatus())
                .addKeyValue("reason", problem.getTitle())
                .addKeyValue("path", path(request))
                .log();
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : request.getDescription(false);
    }
}
