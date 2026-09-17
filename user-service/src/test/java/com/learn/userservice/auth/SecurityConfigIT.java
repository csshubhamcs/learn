package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class SecurityConfigIT extends AbstractAuthIntegrationTest {

    @Test
    void healthProbeIsPublic() {
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void protectedRouteRejectsAnonymousCallers() {
        assertThat(rest.getForEntity("/api/v1/users/me", String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    /**
     * Used to assert only {@code isNotEqualTo(401)} / {@code isNotEqualTo(403)}, which a 500
     * satisfies - so the test passed whether authentication worked or the request blew up
     * behind it. It now registers a user of its own and asserts the one status a fully
     * working authenticated request produces.
     */
    @Test
    void protectedRouteAcceptsAValidToken() {
        String email = "sec-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class);

        String token = tokens().passwordGrant(email, "Password123!");
        ResponseEntity<UserResponse> response =
                rest.exchange("/api/v1/users/me", HttpMethod.GET, bearer(token), UserResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().email()).isEqualTo(email);
    }

    @Test
    void adminRouteRejectsANonAdminToken() {
        String token = tokens().passwordGrant("bob@example.com", "Password123!");
        ResponseEntity<String> response =
                rest.exchange("/api/v1/admin/users", HttpMethod.GET, bearer(token), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
