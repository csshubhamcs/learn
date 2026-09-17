package com.learn.taskservice.common.exception;

/** Thrown when the requested resource does not exist at all, for anyone. Maps to HTTP 404. */
public class ResourceNotFoundException extends BaseException {
    public ResourceNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
