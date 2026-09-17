package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.request.UpdateStatusRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.enums.UserStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class EmailChangeIT extends AbstractAuthIntegrationTest {

    @Test
    void requestingAChangeTriggersKeycloakAndWritesNothingLocally() {
        String email = "chg-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class);
        String token = tokens().passwordGrant(email, "Password123!");

        ResponseEntity<Void> response =
                rest.exchange("/api/v1/users/me/email/change", HttpMethod.POST, bearer(token), Void.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);

        // Nothing about the account's identity has moved - Keycloak's own flow collects the
        // new address, and this service is never called back.
        ResponseEntity<UserResponse> me =
                rest.exchange("/api/v1/users/me", HttpMethod.GET, bearer(token), UserResponse.class);
        assertThat(me.getBody().email()).isEqualTo(email);

        // Proves the request actually reached Keycloak and produced a real UPDATE_EMAIL mail,
        // not just a 202 with nothing behind it - the whole point of routing this through
        // Keycloak's own required-action flow instead of a hand-rolled token.
        String messages = new RestTemplate().getForObject(mailpitApiUrl(), String.class);
        assertThat(messages).contains(email);
    }

    /**
     * Keycloak refuses execute-actions-email for a disabled account with a 400, and the
     * adapter's blanket catch turned that into {@code 503 IDENTITY_PROVIDER_UNAVAILABLE} -
     * so any suspended user could make this service report a healthy identity provider as
     * down, simply by pressing "change my email". A suspended account has no business
     * starting an email change at all, which is what makes 403 the right answer rather than
     * a better-translated 400.
     */
    @Test
    void aSuspendedUserIsRefusedAnEmailChangeAndIsNotToldTheIdentityProviderIsDown() {
        String email = "susp-chg-" + UUID.randomUUID() + "@example.com";
        UserResponse registered = rest.postForEntity(
                        "/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class)
                .getBody();
        String token = tokens().passwordGrant(email, "Password123!");

        // Works while the account is alive, so this cannot pass by the endpoint being broken.
        assertThat(rest.exchange("/api/v1/users/me/email/change", HttpMethod.POST, bearer(token), Void.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(202);

        String adminToken = tokens().passwordGrant("alice@example.com", "Password123!");
        assertThat(rest.exchange(
                                "/api/v1/admin/users/" + registered.id(),
                                HttpMethod.PATCH,
                                bearer(adminToken, new UpdateStatusRequest(UserStatus.SUSPENDED)),
                                String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        ResponseEntity<String> suspended =
                rest.exchange("/api/v1/users/me/email/change", HttpMethod.POST, bearer(token), String.class);

        assertThat(suspended.getStatusCode().value()).isEqualTo(403);
        assertThat(suspended.getBody()).doesNotContain("IDENTITY_PROVIDER_UNAVAILABLE");
    }
}
