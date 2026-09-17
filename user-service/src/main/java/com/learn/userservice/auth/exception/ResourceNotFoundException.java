package com.learn.userservice.auth.exception;

/** Thrown when a requested user (or other resource) does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends BaseException {
    public ResourceNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
