package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.exception.DuplicateIdentifierException;
import com.learn.userservice.auth.exception.KeycloakIntegrationException;
import com.learn.userservice.auth.mapper.UserMapperImpl;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.ActiveUserGuard;
import com.learn.userservice.auth.service.IdentifierNormalizer;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.auth.service.impl.UserServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class UserServiceImplTest {

    UserRepository users;
    KeycloakService keycloak;
    UserServiceImpl service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        keycloak = mock(KeycloakService.class);
        service = new UserServiceImpl(
                users, keycloak, new UserMapperImpl(), new IdentifierNormalizer(), new ActiveUserGuard(users));
    }

    @Test
    void rejectsADuplicateEmailBeforeTouchingKeycloak() {
        when(users.existsByEmailNormalized("a@b.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("A@B.com", "Password123!")))
                .isInstanceOf(DuplicateIdentifierException.class);

        verify(keycloak, never()).createUser(anyString(), anyString());
    }

    // The registration compensation is proved by
    // RegistrationIT.aConstraintViolationOnTheLocalInsertLeavesNoOrphanedKeycloakAccount,
    // against a real database. Mocking users.save to throw synchronously, as this class used
    // to, exercises the one failure mode that could not reach the catch block in production.

    @Test
    void normalizesEmailToLowercase() {
        assertThat(new IdentifierNormalizer().normalizeEmail("  Alice@Example.COM "))
                .isEqualTo("alice@example.com");
    }

    /**
     * Renamed from {@code softDeleteLeavesTheLocalEmailUnchangedWhenKeycloakFails}, whose
     * Javadoc promised "no partial write, no drift between the two systems". It proved
     * neither: the repository is a mock with no transaction behind it, so by the time Keycloak
     * is called {@code softDelete} has already handed it a {@code status=DELETED} entity that
     * the mock silently swallows, and nothing here can observe what a real rollback would
     * leave committed. What this test genuinely establishes is the call ordering inside
     * {@code softDelete} - Keycloak is renamed before the local email column is written, and
     * the account is never disabled if that rename fails - which is worth keeping as a fast
     * unit test.
     *
     * <p>The drift claim itself is proved against a real transaction and a real database by
     * {@code SoftDeleteTransactionIT}, which reads the committed row back afterwards.
     */
    @Test
    void aFailedKeycloakRenameStopsSoftDeleteBeforeTheLocalEmailWrite() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail("old@b.com");
        user.setEmailNormalized("old@b.com");

        when(users.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new KeycloakIntegrationException("Keycloak unavailable", null))
                .when(keycloak)
                .updateEmail(eq(userId), anyString());

        assertThatThrownBy(() -> service.deleteMe(userId)).isInstanceOf(KeycloakIntegrationException.class);

        assertThat(user.getEmailNormalized()).isEqualTo("old@b.com");
        verify(keycloak, never()).setEnabled(any(), anyBoolean());
    }

    /**
     * C1's second half. Ordering protects the local row from a Keycloak failure but nothing
     * protects Keycloak from a local one: the tombstone rename has already been committed on
     * Keycloak's side and no local rollback reaches it, so it has to be undone explicitly.
     */
    @Test
    void aRolledBackLocalTransactionUndoesTheKeycloakTombstoneRename() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail("old@b.com");
        user.setEmailNormalized("old@b.com");

        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("value too long for type character varying(320)"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.deleteMe(userId)).isInstanceOf(DataIntegrityViolationException.class);

            // The rename went through before the local write failed.
            verify(keycloak).updateEmail(eq(userId), startsWith("deleted-"));

            List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
            assertThat(registered).hasSize(1);
            registered.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(keycloak).updateEmail(userId, "old@b.com");
        verify(keycloak).setEnabled(userId, true);
    }
}
