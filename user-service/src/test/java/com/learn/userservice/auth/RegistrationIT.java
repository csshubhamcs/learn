package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.auth.service.UserService;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class RegistrationIT extends AbstractAuthIntegrationTest {

    @Autowired
    KeycloakService keycloak;

    @Autowired
    Keycloak adminClient;

    @Autowired
    UserRepository users;

    @Autowired
    UserService userService;

    @Test
    void registersAUserInKeycloakAndLocally() {
        String email = "reg-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<UserResponse> response = rest.postForEntity(
                "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().email()).isEqualTo(email);
        assertThat(response.getBody().status()).isEqualTo("ACTIVE");
        assertThat(keycloak.exists(response.getBody().id())).isTrue();
    }

    /**
     * I5. The response used to be mapped from the detached instance before any flush, so
     * {@code createdAt} came back null even though the row had one a moment later.
     */
    @Test
    void theRegistrationResponseCarriesThePersistedCreatedAt() {
        String email = "created-at-" + UUID.randomUUID() + "@example.com";

        UserResponse registered = rest.postForEntity(
                        "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class)
                .getBody();

        assertThat(registered.createdAt()).isNotNull();

        String token = tokens().passwordGrant(email, "Password123!");
        UserResponse me = rest.exchange("/api/v1/users/me", HttpMethod.GET, bearer(token), UserResponse.class)
                .getBody();

        assertThat(me.createdAt()).isCloseTo(registered.createdAt(), within(1, ChronoUnit.MILLIS));
    }

    /**
     * I2. The compensation could never fire for the failure it names: {@code User} has an
     * assigned {@code @Id}, so {@code save()} takes the merge path and Hibernate flushed the
     * INSERT at commit - after the catch block had been left behind. This forces a real
     * constraint violation through the real code path: a tombstoned row still holds the
     * unique index on {@code email_normalized}, but {@code @SQLRestriction} hides it from the
     * duplicate pre-check, so registration gets past the check and then collides at INSERT.
     */
    @Test
    void aConstraintViolationOnTheLocalInsertLeavesNoOrphanedKeycloakAccount() {
        String email = "orphan-check-" + UUID.randomUUID() + "@example.com";

        User tombstoned = new User();
        tombstoned.setId(UUID.randomUUID());
        tombstoned.setStatus(UserStatus.DELETED);
        tombstoned.setEmail(email);
        tombstoned.setEmailNormalized(email);
        users.saveAndFlush(tombstoned);

        assertThatThrownBy(() -> userService.register(new RegisterRequest(email, "Password123!")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(adminClient.realm(REALM).users().searchByEmail(email, true)).isEmpty();
    }

    @Test
    void rejectsADuplicateEmailWithConflict() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class);

        ResponseEntity<String> second = rest.postForEntity(
                "/api/v1/auth/register", new RegisterRequest(email.toUpperCase(), "Password123!"), String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("DUPLICATE_IDENTIFIER");
    }

    /**
     * A 4xx from Keycloak means Keycloak answered and refused - the one thing it cannot mean
     * is that Keycloak is unavailable. {@code RegisterRequest} allows 320 characters (the
     * column width); Keycloak's own user table stops at 255, so an address between the two
     * passes Bean Validation and is then refused with a 400. That used to surface as
     * {@code 503 IDENTITY_PROVIDER_UNAVAILABLE}, which meant any anonymous caller could make
     * this service report a healthy identity provider as down, through the one endpoint that
     * needs no token.
     */
    @Test
    void anAddressTheIdentityProviderRefusesIsA400NotAReportedOutage() {
        // 296 characters: a 63-character local part and 63-character domain labels, all
        // inside what @Email accepts, and past Keycloak's 255-character column.
        String email = "u".repeat(63) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + "."
                + "e".repeat(35) + ".com";
        assertThat(email.length()).isBetween(256, 320);

        ResponseEntity<String> response =
                rest.postForEntity("/api/v1/auth/register", new RegisterRequest(email, "Password123!"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).doesNotContain("IDENTITY_PROVIDER_UNAVAILABLE");
        // Keycloak's own error text is not echoed to an unauthenticated caller.
        assertThat(response.getBody()).doesNotContain("Keycloak");
    }

    @Test
    void rejectsAnInvalidEmailWithValidationError() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/auth/register", new RegisterRequest("not-an-email", "Password123!"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }
}
