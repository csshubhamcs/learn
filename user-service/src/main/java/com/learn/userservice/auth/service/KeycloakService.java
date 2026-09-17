package com.learn.userservice.auth.service;

import java.util.List;
import java.util.UUID;

/**
 * The only abstraction in this codebase that talks to Keycloak, the source of truth for
 * identity. Identity-changing methods must only be called from the single write path that
 * keeps the local {@code users} email mirror in step - see {@code UserServiceImpl}.
 */
public interface KeycloakService {

    /** Creates the account; the caller uses the returned id as this service's own primary key for the user. */
    UUID createUser(String email, String password);

    /** Permanently removes the account. Used for admin hard-delete and to compensate a failed registration. */
    void deleteUser(UUID userId);

    /** Enables or disables login without touching identity data - used for suspend/reactivate and tombstoning. */
    void setEnabled(UUID userId, boolean enabled);

    /** Changes the email (and, in this realm, the username that tracks it) Keycloak has on file. */
    void updateEmail(UUID userId, String newEmail);

    /**
     * Triggers Keycloak's own UPDATE_EMAIL required-action email: Keycloak asks the user for
     * the new address and updates itself, with no token or state kept on our side.
     */
    void sendUpdateEmailAction(UUID userId);

    /** Grants realm roles. Roles live only in Keycloak; this service keeps no local copy of them. */
    void assignRealmRoles(UUID userId, List<String> roles);

    /** Whether the account can currently log in. */
    boolean isEnabled(UUID userId);

    /** Whether the account still exists in Keycloak at all. */
    boolean exists(UUID userId);
}
