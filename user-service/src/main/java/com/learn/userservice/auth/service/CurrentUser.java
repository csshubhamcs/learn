package com.learn.userservice.auth.service;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the authenticated user id from the token's sub claim. Never from a path variable. */
@Component
public class CurrentUser {

    /**
     * @throws IllegalStateException if called outside an authenticated JWT request - a
     *     programming error (this should only ever be reached behind Spring Security), not a
     *     condition callers are expected to handle
     */
    public UUID id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Jwt jwt = jwtToken.getToken();
            return UUID.fromString(jwt.getSubject());
        }
        throw new IllegalStateException("No authenticated JWT principal in the security context");
    }
}
