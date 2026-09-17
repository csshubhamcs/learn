package com.learn.userservice.auth.service;

import com.learn.userservice.auth.exception.ResourceNotFoundException;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asserts that the principal behind a self-service request still has a usable account here.
 *
 * <p>A valid signature is not enough. The resource server validates JWTs locally with no
 * introspection, so a token issued before the account was deleted, suspended or purged keeps
 * working until it expires - and {@code setEnabled(false)} only stops Keycloak issuing *new*
 * tokens. It also lets in every principal Keycloak knows but this service has no row for
 * (admin-console users, realm imports, future IdP federation), whose first write would
 * otherwise violate a foreign key and answer 500.
 */
@RequiredArgsConstructor
@Component
public class ActiveUserGuard {

    private final UserRepository users;

    /**
     * @throws ResourceNotFoundException (404) if there is no local row, or it is tombstoned -
     *     matching what {@code GET /users/me} already answers for the same principal
     * @throws AccessDeniedException (403) if the account exists but is not ACTIVE
     */
    @Transactional(readOnly = true)
    public void requireActive(UUID userId) {
        // findById honors @SQLRestriction, so a tombstoned row is already invisible here.
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Account is not active");
        }
    }
}
