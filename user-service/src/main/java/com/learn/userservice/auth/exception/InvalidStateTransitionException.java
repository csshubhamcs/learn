package com.learn.userservice.auth.exception;

/**
 * Thrown when the requested change is valid in isolation but not from the resource's current
 * state - reactivating a tombstoned account, for instance. Maps to HTTP 409: the request is
 * well-formed, and it is the state of the resource that refuses it.
 */
public class InvalidStateTransitionException extends BaseException {
    public InvalidStateTransitionException(String message) {
        super(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
