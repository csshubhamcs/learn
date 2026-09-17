package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.learn.userservice.AbstractIntegrationTest;
import com.learn.userservice.auth.exception.KeycloakIntegrationException;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.auth.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The "no drift between the two systems" claim, against a real transaction and a real
 * database. {@code UserServiceImplTest} makes the same-shaped assertions against a mock
 * repository, which silently accepts a write that a real transaction would either commit or
 * roll back - so it can describe the ordering but cannot prove the outcome. This class
 * proves the outcome: it reads the committed row back after the request has failed.
 *
 * <p>Keycloak is the mock here rather than the database, because the failures under test are
 * Keycloak failures and cannot be induced on a healthy container.
 */
class SoftDeleteTransactionIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    UserService userService;

    @MockitoBean
    KeycloakService keycloak;

    private User persistActive(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail(email);
        user.setEmailNormalized(email);
        return users.saveAndFlush(user);
    }

    /**
     * Keycloak-first ordering, verified where it counts: after the transaction has rolled
     * back, the committed row must still be ACTIVE and still own its address. A mock
     * repository would have accepted the interim {@code status = DELETED} write and reported
     * nothing.
     */
    @Test
    void aKeycloakRenameFailureLeavesTheCommittedRowUntouched() {
        String email = "drift-" + UUID.randomUUID() + "@example.com";
        User saved = persistActive(email);

        doThrow(new KeycloakIntegrationException("Keycloak unavailable", null))
                .when(keycloak)
                .updateEmail(eq(saved.getId()), anyString());

        assertThatThrownBy(() -> userService.deleteMe(saved.getId())).isInstanceOf(KeycloakIntegrationException.class);

        User committed = users.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(committed.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(committed.getEmailNormalized()).isEqualTo(email);
        assertThat(committed.getDeletedAt()).isNull();
        assertThat(committed.getDeletedBy()).isNull();
        // The address was never released, so it is still taken.
        assertThat(users.existsByEmailNormalized(email)).isTrue();
        verify(keycloak, never()).setEnabled(any(), anyBoolean());
    }

    /**
     * The other direction, which ordering alone cannot fix: Keycloak has already been renamed
     * when the local write fails. The overflow is induced for real - a 300-character address
     * plus the 45-character {@code deleted:<uuid>:} prefix exceeds {@code varchar(320)} - so
     * the rollback is Postgres', not a stubbed exception, and the undo is observed after the
     * transaction has actually completed rather than by hand-firing the synchronization.
     */
    @Test
    void aRealRollbackOfTheLocalWriteUndoesTheKeycloakRename() {
        String email = "x".repeat(300 - "@example.com".length()) + "@example.com";
        User saved = persistActive(email);

        assertThatThrownBy(() -> userService.deleteMe(saved.getId())).isInstanceOf(RuntimeException.class);

        // The tombstone rename went through before the local write blew up...
        verify(keycloak).updateEmail(eq(saved.getId()), startsWith("deleted-"));
        // ...and was undone when the transaction rolled back.
        verify(keycloak).updateEmail(saved.getId(), email);
        verify(keycloak).setEnabled(saved.getId(), true);

        User committed = users.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(committed.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(committed.getEmailNormalized()).isEqualTo(email);
    }
}
