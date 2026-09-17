package com.learn.userservice.auth.exception;

/**
 * Base for every domain exception this service throws deliberately (as opposed to an
 * unexpected bug). Carrying an {@link ErrorCode} here, rather than deciding an HTTP status in
 * each {@code throw} site, is what lets {@code GlobalExceptionHandler} translate any of these
 * to the right response with one generic handler instead of one per exception type.
 */
public abstract class BaseException extends RuntimeException {

    private final ErrorCode code;

    protected BaseException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    protected BaseException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
