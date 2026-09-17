package com.learn.taskservice.common.exception;

/**
 * Thrown when a request carries a value this service can name as wrong - an unknown sort
 * field, an unknown sort direction. Maps to HTTP 400 and its message is returned to the
 * client, so it may only ever be constructed with text written for a caller to read.
 *
 * <p>Exists so {@code GlobalExceptionHandler} does not have to blanket-catch
 * {@link IllegalArgumentException}: a library-thrown IAE is a bug in this service, and
 * answering one with 400 plus its internal message both mislabels it and leaks detail.
 */
public class InvalidRequestException extends BaseException {
    public InvalidRequestException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
