package com.learn.userservice.auth.service.impl;

import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.exception.DuplicateIdentifierException;
import com.learn.userservice.auth.exception.ResourceNotFoundException;
import com.learn.userservice.auth.mapper.UserMapper;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.ActiveUserGuard;
import com.learn.userservice.auth.service.IdentifierNormalizer;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.auth.service.UserService;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Self-service account operations: registration, self-delete, and email change.
 *
 * <p><b>Keycloak is the source of truth for identity, including email.</b> The local
 * {@code users.email}/{@code email_normalized} copy is a read model that exists only so admin
 * search/filter/sort can be a SQL query instead of paging Keycloak's Admin API. Only
 * {@link #register} and {@link #softDelete} may write it, and each writes Keycloak first so a
 * Keycloak failure cannot leave the mirror ahead of the source of truth. The reverse ordering
 * problem - Keycloak written, local transaction then rolled back - is not solved by ordering
 * at all and needs the explicit compensation in {@link #undoKeycloakTombstoneOnRollback}.
 *
 * <p><b>Transaction scope (I1).</b> {@link #register} and {@link #requestEmailChange} are not
 * transactional at all - each has a single repository call, so the repository's own
 * transaction is the whole transaction, and no pooled connection is held across a Keycloak
 * round-trip. {@link #softDelete} keeps its caller's transaction on purpose: it is the one
 * path where three writes across two systems have to agree, and both the "a failed Keycloak
 * rename leaves the local row untouched" ordering and the rollback undo in
 * {@link #undoKeycloakTombstoneOnRollback} are defined in terms of that transaction. Shrinking
 * it would mean rebuilding both guarantees against a different mechanism - a much larger change
 * than a shorter connection hold is worth, on the one write path a user performs once.
 *
 * <p>Self-service email change is Keycloak's own UPDATE_EMAIL required action ({@link
 * #requestEmailChange}): Keycloak collects and validates the new address itself and this
 * service is never called back, so the local mirror is not updated when that flow completes.
 * See {@link User}'s Javadoc for the resulting staleness trade-off.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final KeycloakService keycloak;
    private final UserMapper mapper;
    private final IdentifierNormalizer normalizer;
    private final ActiveUserGuard activeUser;

    /**
     * Creates the Keycloak account first, then the local row. If the local write fails,
     * compensates by deleting the Keycloak user (no distributed transaction spans both
     * systems); a failed compensation is logged at ERROR with the orphaned id for manual
     * reconciliation rather than swallowed.
     *
     * <p><b>Deliberately not {@code @Transactional}</b> (I1). With a method-level transaction,
     * the duplicate pre-check acquired a Hikari connection that was then held across the
     * Keycloak HTTP round-trip - measured at 73-148 ms against a *local* Keycloak. Virtual
     * threads remove the servlet-thread ceiling, so the 20-connection pool is the real
     * concurrency limit: a Keycloak latency spike would not merely slow registration down, it
     * would park every connection in the pool on remote I/O and take down reads too.
     *
     * <p>Nothing is given up by dropping it. There is exactly one local write, and
     * {@code saveAndFlush} on the repository is itself transactional, so the insert still
     * commits atomically - it just commits <i>before</i> this method returns instead of after.
     * That makes the compensation below strictly stronger than it was: a failure at commit
     * time (a deferred constraint, a lost connection during commit) used to happen after this
     * try block had already returned, where the catch could never see it. Now it surfaces out
     * of {@code saveAndFlush}, inside the try. The duplicate pre-check was never the guard
     * against a racing registration either - the unique index is, and it still is.
     */
    @Override
    public UserResponse register(RegisterRequest request) {
        String email = normalizer.normalizeEmail(request.email());

        if (users.existsByEmailNormalized(email)) {
            throw new DuplicateIdentifierException("Email already registered");
        }

        UUID keycloakId = keycloak.createUser(email, request.password());

        try {
            User user = new User();
            user.setId(keycloakId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmail(request.email().trim());
            user.setEmailNormalized(email);

            // saveAndFlush, not save. User has an assigned @Id and no @Version, so Spring
            // Data takes the em.merge path, which only *schedules* the INSERT: Hibernate
            // would flush it at commit, after this try block has returned, where the
            // compensation below can no longer fire and a constraint violation becomes a
            // bare 500 with an orphaned Keycloak account behind it. Flushing here also runs
            // @PrePersist, so the instance returned carries the real createdAt - the
            // detached `user` never does, which is why the response maps `saved`.
            User saved = users.saveAndFlush(user);
            return mapper.toResponse(saved);

        } catch (RuntimeException localFailure) {
            // Compensate: the Keycloak user exists but the local write failed.
            try {
                keycloak.deleteUser(keycloakId);
            } catch (RuntimeException compensationFailure) {
                log.error("ORPHANED KEYCLOAK USER keycloakId={} - reconcile manually", keycloakId, compensationFailure);
            }
            throw localFailure;
        }
    }

    /** Looked up by Keycloak subject id, which is also this table's primary key - no mapping table needed. */
    @Override
    @Transactional(readOnly = true)
    public UserResponse findMe(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return mapper.toResponse(user);
    }

    /** Self-service delete is always a soft delete (tombstone); only an admin hard-delete is destructive. */
    @Override
    @Transactional
    public void deleteMe(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        softDelete(user, userId);
    }

    /**
     * Triggers Keycloak's own UPDATE_EMAIL flow: Keycloak emails the user, collects the new
     * address and updates itself. No token or pending-change row on our side, and no local
     * write here - the account's identity has not moved yet when this returns.
     *
     * <p>Also deliberately not {@code @Transactional} (I1), and this was the worst offender of
     * the three: the Admin call below makes Keycloak send mail, so the read-only transaction
     * held a pooled connection across an SMTP conversation - the slowest thing this service
     * ever waits on. There is one read and no write, so the repository's own transaction is
     * the whole transaction; nothing here needs two statements to agree.
     */
    @Override
    public void requestEmailChange(UUID userId) {
        // requireActive, not a bare findById. Keycloak refuses execute-actions-email for a
        // disabled account with a 400, which this service's adapter reported as
        // IDENTITY_PROVIDER_UNAVAILABLE (503) - telling an operator a healthy Keycloak was
        // down, on demand, whenever a suspended user pressed "change my email". Deciding here
        // that a non-ACTIVE account may not start an email change is both the correct answer
        // (403) and one read instead of two, and it keeps "is this principal usable" defined
        // in exactly one place, the same one the profile module asks.
        activeUser.requireActive(userId);
        keycloak.sendUpdateEmailAction(userId);
    }

    /**
     * Tombstones the user: frees the email so it can be reused, and renames the Keycloak user
     * to a synthetic address before disabling it - Keycloak enforces username uniqueness
     * unconditionally, and this realm sets username = email, so an untouched Keycloak user
     * would otherwise keep the real address permanently reserved.
     *
     * <p>Caller must ensure the user is not already DELETED: running this twice prepends a
     * second tombstone prefix to {@code email_normalized} and overwrites the audit fields.
     *
     * <p>Package-private so {@link AdminUserServiceImpl} can reuse it by injecting this
     * concrete class - deliberate, so tombstoning stays defined in exactly one place.
     */
    void softDelete(User user, UUID actor) {
        UUID userId = user.getId();
        String keycloakEmailBeforeTombstoning = user.getEmailNormalized();

        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(Instant.now());
        user.setDeletedBy(actor);
        users.save(user);

        // Keycloak first: if the rename fails the local email column is still untouched.
        keycloak.updateEmail(userId, "deleted-" + UUID.randomUUID() + "@tombstoned.invalid");
        undoKeycloakTombstoneOnRollback(userId, keycloakEmailBeforeTombstoning);

        // saveAndFlush so a failed local write surfaces here rather than at commit.
        user.setEmailNormalized("deleted:" + UUID.randomUUID() + ":" + user.getEmailNormalized());
        users.saveAndFlush(user);

        keycloak.setEnabled(userId, false);
    }

    /**
     * Keycloak has no transaction to enlist in, so a rename that has already gone through
     * survives a rollback of the local write that was supposed to accompany it, leaving the
     * account renamed and disabled in Keycloak with nothing in this database recording it.
     * Registering the undo against the transaction (rather than a local try/catch) covers a
     * rollback decided anywhere in the caller's transaction, including at commit.
     */
    private void undoKeycloakTombstoneOnRollback(UUID userId, String originalEmail) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    keycloak.updateEmail(userId, originalEmail);
                    keycloak.setEnabled(userId, true);
                    log.warn(
                            "Undid the Keycloak tombstone for userId={} after the local transaction rolled back",
                            userId);
                } catch (RuntimeException undoFailure) {
                    log.error("STRANDED KEYCLOAK TOMBSTONE userId={} - reconcile manually", userId, undoFailure);
                }
            }
        });
    }
}
