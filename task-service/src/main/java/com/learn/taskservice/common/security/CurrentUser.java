package com.learn.taskservice.common.security;

import com.learn.taskservice.common.exception.InvalidRequestException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated user id from the token's {@code sub} claim. This is the single
 * source of ownership in this service: a task's {@code userId} is only ever read from here,
 * never from a request body and never from a path variable, so no caller can create or reach
 * another user's task by editing the payload or the URL.
 */
@Component
public class CurrentUser {

    /**
     * @throws IllegalStateException if called outside an authenticated JWT request - a
     *     programming error (this should only ever be reached behind Spring Security), not a
     *     condition callers are expected to handle
     * @throws InvalidRequestException if the subject claim is not a UUID. Keycloak always
     *     issues UUID subjects, but a token minted by some other issuer for this realm would
     *     otherwise blow up as an unhandled IllegalArgumentException and answer 500 for what
     *     is a bad credential, not a server fault.
     */
    public UUID id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Jwt jwt = jwtToken.getToken();
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException e) {
                // Deliberately does not echo the subject back: it is token content.
                throw new InvalidRequestException("The token's subject claim is not a valid user id");
            }
        }
        throw new IllegalStateException("No authenticated JWT principal in the security context");
    }
}
