package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.service.KeycloakService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;

class KeycloakServiceIT extends AbstractAuthIntegrationTest {

    @Autowired
    KeycloakService keycloak;

    /**
     * The raw Admin API client, used to read Keycloak back independently of the class under
     * test. The service account on this realm, not the container's master-realm admin: in this
     * Docker setup a master-realm admin call arriving from the host is treated as external and
     * refused with "HTTPS required" (see AbstractAuthIntegrationTest).
     */
    @Autowired
    Keycloak adminClient;

    /** Reads Keycloak back through its own Admin API rather than through the class under test. */
    private UserRepresentation keycloakUser(UUID id) {
        return adminClient.realm(REALM).users().get(id.toString()).toRepresentation();
    }

    private List<String> realmRoleNames(UUID id) {
        return adminClient.realm(REALM).users().get(id.toString()).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .toList();
    }

    @Test
    void createsDisablesAndDeletesAUser() {
        String email = "kc-" + UUID.randomUUID() + "@example.com";

        UUID id = keycloak.createUser(email, "Password123!");
        assertThat(id).isNotNull();
        assertThat(keycloak.exists(id)).isTrue();
        assertThat(keycloak.isEnabled(id)).isTrue();

        keycloak.setEnabled(id, false);
        assertThat(keycloak.isEnabled(id)).isFalse();

        keycloak.deleteUser(id);
        assertThat(keycloak.exists(id)).isFalse();
    }

    /**
     * The name promises two behaviours; the only assertion used to be {@code exists(id)}, which
     * holds if both methods are no-ops. Each effect is now read back from Keycloak itself.
     */
    @Test
    void assignsRealmRolesAndUpdatesEmail() {
        String email = "kc-" + UUID.randomUUID() + "@example.com";
        UUID id = keycloak.createUser(email, "Password123!");

        assertThat(realmRoleNames(id)).doesNotContain("ADMIN");

        keycloak.assignRealmRoles(id, List.of("ADMIN"));
        assertThat(realmRoleNames(id)).contains("ADMIN");

        String newEmail = "changed-" + email;
        keycloak.updateEmail(id, newEmail);

        UserRepresentation after = keycloakUser(id);
        assertThat(after.getEmail()).isEqualTo(newEmail);
        // Username tracks email in this realm - that is what frees the old address for reuse,
        // so a rename that moved only the email would leave the old one permanently reserved.
        assertThat(after.getUsername()).isEqualTo(newEmail);
        assertThat(after.isEmailVerified()).isTrue();

        keycloak.deleteUser(id);
    }
}
