package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.learn.userservice.AbstractIntegrationTest;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.auth.service.UserService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class UserRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    UserService userService;

    // The claim under test is a database one - that the production tombstone prefix releases
    // the address from uk_users_email_normalized - so Keycloak is stubbed out rather than
    // started. This class extends AbstractIntegrationTest, which runs no Keycloak container.
    @MockitoBean
    KeycloakService keycloak;

    private User persistUser(UserStatus status, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setStatus(status);
        user.setEmail(email);
        user.setEmailNormalized(email.toLowerCase(java.util.Locale.ROOT));
        return users.saveAndFlush(user);
    }

    private User persistUser(UserStatus status) {
        return persistUser(status, UUID.randomUUID() + "@example.com");
    }

    @Test
    void savesAndFindsAnActiveUser() {
        User saved = persistUser(UserStatus.ACTIVE);
        assertThat(users.findById(saved.getId())).isPresent();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void softDeletedUsersAreHiddenFromNormalQueriesButVisibleToTheAdminQuery() {
        User saved = persistUser(UserStatus.ACTIVE);
        saved.setStatus(UserStatus.DELETED);
        saved.setDeletedAt(Instant.now());
        users.saveAndFlush(saved);

        assertThat(users.findById(saved.getId())).isEmpty();
        assertThat(users.findByIdIncludingDeleted(saved.getId())).isPresent();
    }

    /**
     * This test used to build the tombstone string itself ({@code "deleted:" + randomUUID() +
     * ":" + …}) and then assert that the value it had just written differed from the original -
     * true by construction, and still true with production tombstoning deleted entirely. It
     * now drives {@link com.learn.userservice.auth.service.UserService#deleteMe} instead, so
     * the prefix, the status transition and the audit fields all come from the production path
     * and the release of the address is proved against the real unique index rather than
     * asserted about a literal.
     *
     * <p>Keycloak is mocked rather than containerised because the claim under test is a
     * database one; the two-system half of soft delete is covered against a live Keycloak by
     * {@code SelfServiceIT.softDeleteHidesTheUserDisablesKeycloakAndFreesTheEmail}.
     */
    @Test
    void theProductionSoftDeletePathReleasesTheEmailForReRegistration() {
        String email = "alice-" + UUID.randomUUID() + "@example.com";
        String normalized = email.toLowerCase(java.util.Locale.ROOT);
        User saved = persistUser(UserStatus.ACTIVE, email);

        assertThat(users.existsByEmailNormalized(normalized)).isTrue();

        userService.deleteMe(saved.getId());

        User tombstoned = users.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(tombstoned.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(tombstoned.getDeletedAt()).isNotNull();
        assertThat(tombstoned.getEmailNormalized()).endsWith(":" + normalized).isNotEqualTo(normalized);
        assertThat(users.existsByEmailNormalized(normalized)).isFalse();
        verify(keycloak).setEnabled(saved.getId(), false);

        // The real proof: uk_users_email_normalized no longer holds the address, so a second
        // row can take it. Without the production prefix this insert fails the unique index.
        User reRegistered = persistUser(UserStatus.ACTIVE, email);
        assertThat(reRegistered.getId()).isNotEqualTo(saved.getId());
    }

    @Test
    void searchExcludesDeletedUsersByDefaultButIncludesThemWhenAsked() {
        User active = persistUser(UserStatus.ACTIVE);
        User deleted = persistUser(UserStatus.ACTIVE);
        deleted.setStatus(UserStatus.DELETED);
        deleted.setDeletedAt(Instant.now());
        users.saveAndFlush(deleted);

        Page<User> excludingDeleted =
                users.search(null, false, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "created_at")));
        assertThat(excludingDeleted.getContent())
                .extracting(User::getId)
                .contains(active.getId())
                .doesNotContain(deleted.getId());

        Page<User> includingDeleted =
                users.search(null, true, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "created_at")));
        assertThat(includingDeleted.getContent()).extracting(User::getId).contains(active.getId(), deleted.getId());
    }

    @Test
    void searchReportsTotalsAndPagesCorrectly() {
        persistUser(UserStatus.ACTIVE);
        persistUser(UserStatus.ACTIVE);
        persistUser(UserStatus.ACTIVE);

        Page<User> firstPage =
                users.search(null, false, null, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "created_at")));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isGreaterThanOrEqualTo(3);
        assertThat(firstPage.getTotalPages()).isGreaterThanOrEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.hasPrevious()).isFalse();
    }

    @Test
    void searchFiltersByNormalizedEmail() {
        User saved = persistUser(UserStatus.ACTIVE, "Bob@Example.com");

        Page<User> matches =
                users.search("bob", false, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "created_at")));
        assertThat(matches.getContent()).extracting(User::getId).contains(saved.getId());

        Page<User> noMatches = users.search(
                "nobody-with-this-name",
                false,
                null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "created_at")));
        assertThat(noMatches.getContent()).extracting(User::getId).doesNotContain(saved.getId());
    }

    @Test
    void searchFiltersByExactStatus() {
        User active = persistUser(UserStatus.ACTIVE);
        User suspended = persistUser(UserStatus.SUSPENDED);

        Page<User> activeOnly = users.search(
                null,
                false,
                UserStatus.ACTIVE.name(),
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "created_at")));

        assertThat(activeOnly.getContent())
                .extracting(User::getId)
                .contains(active.getId())
                .doesNotContain(suspended.getId());
    }

    @Test
    void searchSortsByEmailAscending() {
        String suffix = "-" + UUID.randomUUID() + "@example.com";
        User userA = persistUser(UserStatus.ACTIVE, "aaa" + suffix);
        User userZ = persistUser(UserStatus.ACTIVE, "zzz" + suffix);

        Page<User> sorted = users.search(
                suffix.substring(1, 8), false, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "email")));

        assertThat(sorted.getContent()).extracting(User::getId).containsExactly(userA.getId(), userZ.getId());
    }
}
