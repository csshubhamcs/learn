package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.service.KeycloakService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class SelfServiceIT extends AbstractAuthIntegrationTest {

    @Autowired
    KeycloakService keycloak;

    /**
     * Used to assert {@code isIn(200, 404)} against realm-import bob, which is true whether or
     * not {@code findMe} works at all. It now registers its own user, so the outcome is a
     * single known value, and checks the record actually belongs to the caller - the whole
     * point of an endpoint that resolves identity from the JWT rather than a path variable.
     */
    @Test
    void returnsTheCallersOwnRecord() {
        String email = "me-" + UUID.randomUUID() + "@example.com";
        UserResponse created = rest.postForEntity(
                        "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class)
                .getBody();

        String token = tokens().passwordGrant(email, "Password123!");
        ResponseEntity<UserResponse> response =
                rest.exchange("/api/v1/users/me", HttpMethod.GET, bearer(token), UserResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().id()).isEqualTo(created.id());
        assertThat(response.getBody().email()).isEqualTo(email);
        assertThat(response.getBody().status()).isEqualTo(UserStatus.ACTIVE.name());
    }

    /** The other half of the same contract: a principal Keycloak knows but this service has no row for is 404, not 200 and not 500. */
    @Test
    void aPrincipalWithNoLocalRowGets404() {
        String token = tokens().passwordGrant("bob@example.com", "Password123!");

        ResponseEntity<String> response =
                rest.exchange("/api/v1/users/me", HttpMethod.GET, bearer(token), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("NOT_FOUND");
    }

    @Test
    void softDeleteHidesTheUserDisablesKeycloakAndFreesTheEmail() {
        String email = "del-" + UUID.randomUUID() + "@example.com";
        UserResponse created = rest.postForEntity(
                        "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class)
                .getBody();

        String token = tokens().passwordGrant(email, "Password123!");

        ResponseEntity<Void> deleted = rest.exchange("/api/v1/users/me", HttpMethod.DELETE, bearer(token), Void.class);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);

        // Keycloak user is disabled, not deleted.
        assertThat(keycloak.exists(created.id())).isTrue();
        assertThat(keycloak.isEnabled(created.id())).isFalse();

        // The email is released, so the same address can register again.
        ResponseEntity<UserResponse> reRegistered = rest.postForEntity(
                "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class);
        assertThat(reRegistered.getStatusCode().value()).isEqualTo(201);
        assertThat(reRegistered.getBody().id()).isNotEqualTo(created.id());
    }
}
