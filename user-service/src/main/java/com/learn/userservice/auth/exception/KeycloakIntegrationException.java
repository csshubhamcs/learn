package com.learn.userservice.auth.exception;

/**
 * Thrown when a call to Keycloak's Admin API fails or returns an unexpected status. Maps to
 * HTTP 503: the request itself was valid, but the identity provider this service depends on
 * to fulfil it was not - a client retry is the correct next step, unlike a 500.
 */
public class KeycloakIntegrationException extends BaseException {
    public KeycloakIntegrationException(String message, Throwable cause) {
        super(ErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, message, cause);
    }
}
