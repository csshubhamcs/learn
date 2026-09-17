package com.learn.userservice.auth.exception;

/** Thrown when a registration or email change targets an identifier already in use. Maps to HTTP 409. */
public class DuplicateIdentifierException extends BaseException {
    public DuplicateIdentifierException(String message) {
        super(ErrorCode.DUPLICATE_IDENTIFIER, message);
    }
}
