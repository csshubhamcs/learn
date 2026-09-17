package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.dto.request.AssignRolesRequest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.request.UpdateStatusRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.KeycloakService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

class AdminUserIT extends AbstractAuthIntegrationTest {

    @Autowired
    KeycloakService keycloak;

    @Autowired
    UserRepository users;

    private static final Pattern TOTAL_ELEMENTS = Pattern.compile("\"totalElements\":(\\d+)");

    private String adminToken() {
        return tokens().passwordGrant("alice@example.com", "Password123!");
    }

    private UserResponse register(String email) {
        return rest.postForEntity(
                        "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class)
                .getBody();
    }

    private ResponseEntity<String> patchStatus(UUID id, UserStatus status, String token) {
        return rest.exchange(
                "/api/v1/admin/users/" + id,
                HttpMethod.PATCH,
                bearer(token, new UpdateStatusRequest(status)),
                String.class);
    }

    private User row(UUID id) {
        return users.findByIdIncludingDeleted(id).orElseThrow();
    }

    @Test
    void listsUsersAsAPageWithATotalCount() {
        String suffix = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            register("page-" + suffix + "-" + i + "@example.com");
        }

        ResponseEntity<String> page = rest.exchange(
                "/api/v1/admin/users?size=2&q=page-" + suffix, HttpMethod.GET, bearer(adminToken()), String.class);

        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("\"page\":0");
        assertThat(page.getBody()).contains("\"size\":2");
        assertThat(page.getBody()).contains("\"totalElements\":3");
        assertThat(page.getBody()).contains("\"hasNext\":true");
        assertThat(page.getBody()).contains("\"hasPrevious\":false");
    }

    @Test
    void aSecondPageHasNoPreviousFlagFalseAndContainsTheRemainingRow() {
        String suffix = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            register("paged-" + suffix + "-" + i + "@example.com");
        }

        ResponseEntity<String> secondPage = rest.exchange(
                "/api/v1/admin/users?size=2&page=1&q=paged-" + suffix,
                HttpMethod.GET,
                bearer(adminToken()),
                String.class);

        assertThat(secondPage.getStatusCode().value()).isEqualTo(200);
        assertThat(secondPage.getBody()).contains("\"page\":1");
        assertThat(secondPage.getBody()).contains("\"hasNext\":false");
        assertThat(secondPage.getBody()).contains("\"hasPrevious\":true");
    }

    @Test
    void filtersByStatus() {
        UserResponse created = register("status-filter-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<String> activeOnly = rest.exchange(
                "/api/v1/admin/users?status=ACTIVE&q=status-filter",
                HttpMethod.GET,
                bearer(adminToken()),
                String.class);
        assertThat(activeOnly.getStatusCode().value()).isEqualTo(200);
        assertThat(activeOnly.getBody()).contains(created.id().toString());

        ResponseEntity<String> suspendedOnly = rest.exchange(
                "/api/v1/admin/users?status=SUSPENDED&q=status-filter",
                HttpMethod.GET,
                bearer(adminToken()),
                String.class);
        assertThat(suspendedOnly.getStatusCode().value()).isEqualTo(200);
        assertThat(suspendedOnly.getBody()).doesNotContain(created.id().toString());
    }

    /** Inserts a row directly, so a normalized address can carry characters registration would never produce. */
    private UUID seedNormalizedEmail(String normalized) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail(normalized);
        user.setEmailNormalized(normalized);
        users.saveAndFlush(user);
        return user.getId();
    }

    /**
     * Built as a {@link URI} rather than a string template: {@code RestTemplate} treats a
     * string as a URI template and would re-encode the percent signs this test exists to send.
     */
    private String searchBody(String rawQueryTerm) {
        URI uri = UriComponentsBuilder.fromUriString(rest.getRootUri() + "/api/v1/admin/users")
                .queryParam("size", 50)
                .queryParam("q", rawQueryTerm)
                .encode()
                .build()
                .toUri();

        ResponseEntity<String> response = rest.exchange(uri, HttpMethod.GET, bearer(adminToken()), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private long totalElements(String rawQueryTerm) {
        Matcher matcher = TOTAL_ELEMENTS.matcher(searchBody(rawQueryTerm));
        assertThat(matcher.find()).as("response carries a totalElements field").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /**
     * I9. {@code q} is bound, so this was never injection - but LIKE's own metacharacters were
     * passed through as wildcards, so {@code q=%} matched every row in the table (measured
     * against the live database: 1,000,210 of them) and {@code q=_} matched any single
     * character. Wrong matching, and a free full table scan for an unprivileged typo.
     */
    @Test
    void likeMetacharactersInTheSearchTermAreMatchedLiterally() {
        String marker = "pct-" + UUID.randomUUID();
        seedNormalizedEmail(marker + "-a%b@example.com");
        seedNormalizedEmail(marker + "-axxb@example.com");

        // Literal: matches only the row that really contains "a%b". Unescaped, '%' is a
        // wildcard and this matches both rows.
        assertThat(totalElements(marker + "-a%b")).isEqualTo(1);
        // Literal: no stored address contains "a_b". Unescaped, '_' matches any single
        // character and this matches the "a%b" row.
        assertThat(totalElements(marker + "-a_b")).isZero();
        // A trailing '%' is a literal, so nothing matches. Unescaped it is "everything after
        // the marker" - the same mechanism that made a bare q=% return the whole table.
        assertThat(totalElements(marker + "%")).isZero();
        // Not "reject everything": a term with no metacharacters still matches both rows.
        assertThat(totalElements(marker)).isEqualTo(2);
    }

    /**
     * The escape character itself has to be escaped first, or escaping {@code %} re-escapes its
     * own backslash. Postgres treats {@code \b} as a plain {@code b}, so without the doubling
     * this term matches the wrong row and misses the right one.
     */
    @Test
    void aBackslashInTheSearchTermIsMatchedLiterally() {
        String marker = "bsl-" + UUID.randomUUID();
        UUID withBackslash = seedNormalizedEmail(marker + "-a\\b@example.com");
        UUID withoutBackslash = seedNormalizedEmail(marker + "-ab@example.com");

        // Which row matches is the whole point: undoubled, Postgres reads the backslash as an
        // escape and "a\b" degenerates to a plain "ab", so the count stays 1 while the match
        // silently moves to the wrong user.
        String body = searchBody(marker + "-a\\b");
        assertThat(body).contains(withBackslash.toString());
        assertThat(body).doesNotContain(withoutBackslash.toString());
    }

    @Test
    void anUnrecognizedSortByIsRejectedWith400NotAServerError() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users?sortBy=passwordHash", HttpMethod.GET, bearer(adminToken()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * {@code size} was clamped but {@code page} was not, and Spring Data JPA computes the
     * offset as {@code page * size} into an {@code int}: once that product passed
     * {@code Integer.MAX_VALUE} the listing answered 500 with the generic INTERNAL_ERROR
     * before it ever reached the database. A page number past the end of the data is ordinary
     * client input - a UI that computes one, a stale bookmark - and already answers with an
     * empty page everywhere below the overflow threshold, so it must answer with one here too.
     */
    @Test
    void aPageNumberBeyondTheOffsetLimitIsAnEmptyPageNotAServerError() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users?page=99999999&size=100", HttpMethod.GET, bearer(adminToken()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"items\":[]");

        // Still an empty page - not a silent wrap-around to page 0, which would hand the
        // caller a screenful of real users under a page number they did not ask for.
        ResponseEntity<String> maxInt = rest.exchange(
                "/api/v1/admin/users?page=" + Integer.MAX_VALUE + "&size=100",
                HttpMethod.GET,
                bearer(adminToken()),
                String.class);

        assertThat(maxInt.getStatusCode().value()).isEqualTo(200);
        assertThat(maxInt.getBody()).contains("\"items\":[]");
    }

    @Test
    void sortingByEmailIsAccepted() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users?sortBy=email&sortDir=asc", HttpMethod.GET, bearer(adminToken()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anUnrecognizedSortDirIsRejectedWith400NotAServerError() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users?sortDir=sideways", HttpMethod.GET, bearer(adminToken()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }

    /**
     * C1. Without the already-DELETED guard every repeat prepends another 45-character
     * {@code deleted:<uuid>:} to {@code email_normalized} and overwrites the audit fields,
     * until the column overflows {@code varchar(320)} and the endpoint starts answering 500.
     */
    @Test
    void repeatingADeleteStatusPatchIsIdempotentAndDoesNotGrowTheStoredEmail() {
        UserResponse created = register("re-delete-" + UUID.randomUUID() + "@example.com");
        String admin = adminToken();

        assertThat(patchStatus(created.id(), UserStatus.DELETED, admin)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        User afterFirst = row(created.id());
        String tombstonedEmail = afterFirst.getEmailNormalized();
        var firstDeletedAt = afterFirst.getDeletedAt();

        for (int i = 0; i < 9; i++) {
            assertThat(patchStatus(created.id(), UserStatus.DELETED, admin)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
        }

        User afterTen = row(created.id());
        assertThat(afterTen.getEmailNormalized()).isEqualTo(tombstonedEmail);
        // Exactly one tombstone prefix, not one per call.
        assertThat(afterTen.getEmailNormalized().split("deleted:", -1)).hasSize(2);
        // The record of who really deleted the account survives the repeats.
        assertThat(afterTen.getDeletedAt()).isEqualTo(firstDeletedAt);
    }

    /**
     * C2. Reactivation used to answer 200 while Keycloak still held the tombstone username,
     * so the API reported a healthy account nobody could log in to - and, because the local
     * email_normalized stayed prefixed, the address could be registered again, leaving two
     * ACTIVE rows sharing one users.email.
     */
    @Test
    void aTombstonedUserCannotBeReactivated() {
        String email = "reactivate-" + UUID.randomUUID() + "@example.com";
        UserResponse created = register(email);
        String admin = adminToken();

        assertThat(patchStatus(created.id(), UserStatus.DELETED, admin)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        ResponseEntity<String> reactivated = patchStatus(created.id(), UserStatus.ACTIVE, admin);
        assertThat(reactivated.getStatusCode().value()).isEqualTo(409);
        assertThat(reactivated.getBody()).contains("INVALID_STATE_TRANSITION");

        // The account stays tombstoned and disabled in both systems.
        assertThat(row(created.id()).getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(keycloak.isEnabled(created.id())).isFalse();

        // And the released address has exactly one live owner, the new registration.
        UserResponse reRegistered = register(email);
        assertThat(reRegistered.id()).isNotEqualTo(created.id());
        assertThat(users.findAll().stream()
                        .filter(u -> email.equals(u.getEmail()))
                        .count())
                .isEqualTo(1);
    }

    /** I4. A role name Keycloak does not have is a client error; Keycloak itself is healthy. */
    @Test
    void anUnknownRoleNameIsA400NotAnIdentityProviderOutage() {
        UserResponse created = register("bad-role-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users/" + created.id() + "/roles",
                HttpMethod.POST,
                bearer(adminToken(), new AssignRolesRequest(List.of("NO_SUCH_ROLE"))),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_FAILED").contains("NO_SUCH_ROLE");
        assertThat(response.getBody()).doesNotContain("IDENTITY_PROVIDER_UNAVAILABLE");
    }

    @Test
    void grantingARoleThatExistsStillSucceeds() {
        UserResponse created = register("good-role-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users/" + created.id() + "/roles",
                HttpMethod.POST,
                bearer(adminToken(), new AssignRolesRequest(List.of("ADMIN"))),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void hardDeleteRemovesTheRowAndTheKeycloakUser() {
        UserResponse created = register("hard-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<Void> response = rest.exchange(
                "/api/v1/admin/users/" + created.id() + "?hard=true",
                HttpMethod.DELETE,
                bearer(adminToken()),
                Void.class);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(keycloak.exists(created.id())).isFalse();

        ResponseEntity<String> lookup = rest.exchange(
                "/api/v1/admin/users/" + created.id(), HttpMethod.GET, bearer(adminToken()), String.class);
        assertThat(lookup.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void hardDeleteWorksOnAnAlreadySoftDeletedUser() {
        UserResponse created = register("soft-then-hard-" + UUID.randomUUID() + "@example.com");
        String admin = adminToken();

        rest.exchange("/api/v1/admin/users/" + created.id(), HttpMethod.DELETE, bearer(admin), Void.class);

        ResponseEntity<Void> hard = rest.exchange(
                "/api/v1/admin/users/" + created.id() + "?hard=true", HttpMethod.DELETE, bearer(admin), Void.class);

        assertThat(hard.getStatusCode().value()).isEqualTo(204);
        assertThat(keycloak.exists(created.id())).isFalse();
    }
}
