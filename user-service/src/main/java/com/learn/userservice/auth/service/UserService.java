package com.learn.userservice.auth.service;

import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import java.util.UUID;

/**
 * Self-service account operations - the ones a user performs on their own account, as opposed
 * to {@code AdminUserService}, which acts on other users' accounts. See the implementation's
 * class Javadoc for the email ownership contract every implementation must uphold.
 */
public interface UserService {

    /** Creates a Keycloak account and the matching local rows; rejects an email already in use. */
    UserResponse register(RegisterRequest request);

    /** Returns the caller's own profile, resolved from the token's subject - never from a path variable. */
    UserResponse findMe(UUID userId);

    /** Soft-deletes (tombstones) the caller's own account and frees its email for reuse. */
    void deleteMe(UUID userId);

    /** Triggers Keycloak's own UPDATE_EMAIL flow; Keycloak collects the new address itself. */
    void requestEmailChange(UUID userId);
}
