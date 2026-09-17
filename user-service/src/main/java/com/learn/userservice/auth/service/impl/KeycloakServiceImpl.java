package com.learn.userservice.auth.service.impl;

import com.learn.userservice.auth.exception.DuplicateIdentifierException;
import com.learn.userservice.auth.exception.InvalidRequestException;
import com.learn.userservice.auth.exception.KeycloakIntegrationException;
import com.learn.userservice.auth.exception.ResourceNotFoundException;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.config.AppProperties;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

/**
 * Talks to Keycloak's Admin REST API via the official admin client. Never logs a token,
 * password or secret - only ids and outcome/status codes, which is why every log line below
 * mentions {@code userId} or an HTTP status but never a credential.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KeycloakServiceImpl implements KeycloakService {

    private final Keycloak keycloak;
    private final AppProperties properties;

    private RealmResource realm() {
        return keycloak.realm(properties.keycloak().realm());
    }

    @Override
    public UUID createUser(String email, String password) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);
        // USER REQUIREMENT: the realm has verifyEmail=false, so a newly created user must be
        // able to obtain a token immediately with no mail step. Marking the address verified
        // up front (instead of the brief's VERIFY_EMAIL required action) means Keycloak never
        // blocks login on an unfulfilled required action.
        user.setEmailVerified(true);
        // firstName/lastName are deliberately NOT set. Keycloak's declarative User Profile
        // marks them required by default, and an unmet requirement silently attaches a dynamic
        // VERIFY_PROFILE action that blocks first login with "Account is not fully set up" --
        // invisible in requiredActions via GET, which makes it very hard to diagnose.
        // The fix belongs in the realm, not here: the User Profile marks both optional (applied
        // by the keycloak-bootstrap service). Registration takes only an email and a password,
        // and names are profile data owned by this service's own tables -- inventing a
        // placeholder name to satisfy an identity-provider policy would put fabricated data in
        // front of users and in every email template.

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));

        try (Response response = realm().users().create(user)) {
            int status = response.getStatus();
            if (status == 409) {
                throw new DuplicateIdentifierException("Email already registered");
            }
            // A 4xx means Keycloak answered and refused - most often because the address is
            // longer than the 255 characters its own user table allows, which Bean Validation
            // (@Size(max = 320)) lets through. Reporting that as IDENTITY_PROVIDER_UNAVAILABLE
            // told an operator a healthy Keycloak was down, and let any anonymous caller
            // produce that signal on demand through the one permitAll endpoint. The detail
            // stays server-side: the response carries a fixed string, since a Keycloak error
            // body is not ours to echo to an unauthenticated caller.
            if (status >= 400 && status < 500) {
                log.warn("Keycloak refused user creation with status {}", status);
                throw new InvalidRequestException("The identity provider rejected these account details");
            }
            if (status != 201) {
                throw new KeycloakIntegrationException("Keycloak rejected user creation with status " + status, null);
            }
            String location = response.getLocation().getPath();
            UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
            log.info("Created Keycloak user id={}", id);
            return id;
        } catch (DuplicateIdentifierException | InvalidRequestException | KeycloakIntegrationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to create user in Keycloak", e);
        }
    }

    @Override
    public void deleteUser(UUID userId) {
        try (Response response = realm().users().delete(userId.toString())) {
            int status = response.getStatus();
            if (status != 204 && status != 404) {
                throw new KeycloakIntegrationException("Keycloak rejected user deletion with status " + status, null);
            }
            log.info("Deleted Keycloak user id={} status={}", userId, status);
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to delete user in Keycloak", e);
        }
    }

    @Override
    public void setEnabled(UUID userId, boolean enabled) {
        try {
            var resource = realm().users().get(userId.toString());
            UserRepresentation representation = resource.toRepresentation();
            representation.setEnabled(enabled);
            resource.update(representation);
            log.info("Set Keycloak user id={} enabled={}", userId, enabled);
        } catch (NotFoundException e) {
            // Keycloak answering "no such user" is an expected, everyday condition, not an
            // outage: 404, never the 503 that sends an operator to check a healthy Keycloak.
            throw new ResourceNotFoundException("User not found in Keycloak: " + userId);
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to update enabled flag in Keycloak", e);
        }
    }

    @Override
    public void updateEmail(UUID userId, String newEmail) {
        try {
            var resource = realm().users().get(userId.toString());
            UserRepresentation representation = resource.toRepresentation();
            // Username tracks email in this realm (registration sets both to the same value),
            // so it is kept in sync here too. The realm enables editUsernameAllowed for exactly
            // this: without it Keycloak rejects the change with error-user-attribute-read-only,
            // which would leave a stale username permanently pinned to the old address - and
            // that stale username is exactly what blocks the old address from ever being
            // reused by a new registration (see UserServiceImpl.softDelete).
            representation.setUsername(newEmail);
            representation.setEmail(newEmail);
            representation.setEmailVerified(true);
            resource.update(representation);
            log.info("Updated email for Keycloak user id={}", userId);
        } catch (NotFoundException e) {
            throw new ResourceNotFoundException("User not found in Keycloak: " + userId);
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to update email in Keycloak", e);
        }
    }

    @Override
    public void sendUpdateEmailAction(UUID userId) {
        try {
            realm().users().get(userId.toString()).executeActionsEmail(List.of("UPDATE_EMAIL"));
            log.info("Sent UPDATE_EMAIL action email for Keycloak user id={}", userId);
        } catch (NotFoundException e) {
            throw new ResourceNotFoundException("User not found in Keycloak: " + userId);
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to send UPDATE_EMAIL action email", e);
        }
    }

    @Override
    public void assignRealmRoles(UUID userId, List<String> roles) {
        // Resolved one at a time so a role Keycloak does not have can be named in the error.
        // A typo'd role name is a client error (400) and a user that no longer exists is a
        // 404 - neither means the identity provider is unavailable, which is what a blanket
        // RuntimeException catch around this whole method would have reported.
        List<RoleRepresentation> representations = new ArrayList<>(roles.size());
        for (String role : roles) {
            representations.add(resolveRealmRole(role));
        }

        try {
            realm().users().get(userId.toString()).roles().realmLevel().add(representations);
            log.info("Assigned realm roles to Keycloak user id={} roles={}", userId, roles);
        } catch (NotFoundException e) {
            throw new ResourceNotFoundException("User not found in Keycloak: " + userId);
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to assign realm roles in Keycloak", e);
        }
    }

    private RoleRepresentation resolveRealmRole(String role) {
        try {
            return realm().roles().get(role).toRepresentation();
        } catch (NotFoundException e) {
            throw new InvalidRequestException("No such realm role: " + role);
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to read realm roles from Keycloak", e);
        }
    }

    @Override
    public boolean isEnabled(UUID userId) {
        try {
            return realm().users().get(userId.toString()).toRepresentation().isEnabled();
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to read user from Keycloak", e);
        }
    }

    @Override
    public boolean exists(UUID userId) {
        try {
            realm().users().get(userId.toString()).toRepresentation();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            throw new KeycloakIntegrationException("Failed to read user from Keycloak", e);
        }
    }
}
