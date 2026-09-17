package com.learn.taskservice.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the {@link ApiError} shape at the HTTP boundary, and is the one
 * place that decides which status code each failure gets. The guiding rule: an expected,
 * everyday condition - bad input, a missing row, someone else's task - must never come back
 * as 500. A 500 here should mean "our code has a bug or a dependency is down", nothing else.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiError> handleBase(BaseException ex, WebRequest request) {
        ErrorCode code = ex.getCode();
        if (code.status().is5xxServerError()) {
            log.error("Request failed with {}", code, ex);
        } else {
            log.warn("Request rejected with {}: {}", code, ex.getMessage());
        }
        return build(code, ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", request, fields);
    }

    /** Spring Security's own denial (a USER token on an admin route) - not the ownership check. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return build(ErrorCode.FORBIDDEN, "Access denied", request, Map.of());
    }

    // Requests to a nonexistent path are routine (bots, stale bookmarks, typos) and would
    // otherwise flood production logs at ERROR via handleUnexpected. Not logged at all here.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex, WebRequest request) {
        return build(ErrorCode.NOT_FOUND, "No such endpoint", request, Map.of());
    }

    // There is deliberately NO handler for IllegalArgumentException. Any IAE reaching here
    // comes from a library (Jackson, Hibernate, the JDK) and is a bug in this service, not a
    // client error: answering 400 with its message both mislabels the failure and echoes
    // internal text to the caller. Caller-supplied values this service can name as wrong -
    // an unknown sortBy, an unknown sortDir - throw InvalidRequestException instead.

    // A query/path parameter Spring cannot convert to its target type - ?status=NONSENSE
    // against the TaskStatus parameter, or a path id that is not a UUID - is a client input
    // error, not a server fault. Without this handler it falls through to handleUnexpected
    // and answers 500.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        log.warn("Request rejected with {}: {}", ErrorCode.VALIDATION_FAILED, message);
        return build(ErrorCode.VALIDATION_FAILED, message, request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request, Map.of());
    }

    private ResponseEntity<ApiError> build(
            ErrorCode code, String message, WebRequest request, Map<String, String> fields) {
        ApiError body = new ApiError(
                Instant.now(),
                code.status().value(),
                code.name(),
                message,
                pathOf(request),
                // "traceId" is the key Micrometer Tracing's MDC instrumentation populates, so
                // this field starts carrying a real distributed trace id the moment tracing is
                // enabled, with no change to the API contract. Null until then.
                MDC.get("traceId"),
                fields.isEmpty() ? null : fields);
        return ResponseEntity.status(code.status()).body(body);
    }

    private String pathOf(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            HttpServletRequest raw = servletRequest.getRequest();
            return raw.getRequestURI();
        }
        return null;
    }
}
