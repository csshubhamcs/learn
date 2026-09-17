package com.learn.taskservice.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Every machine-readable error this API can return, each pinned to exactly one HTTP status.
 * Fixing the status here, rather than at each throw site, means the mapping between a domain
 * failure and its HTTP status is defined once and cannot drift between call sites.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
