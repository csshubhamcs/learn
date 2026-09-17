package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The edge: who gets in at all, before any ownership question arises. */
class SecurityConfigIT extends AbstractTaskIntegrationTest {

    @Test
    void healthProbeIsPublic() {
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void theOpenApiDocumentIsPublic() {
        ResponseEntity<String> spec = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(spec.getStatusCode().value()).isEqualTo(200);
        // Proves the OpenApiCustomizer ran: 401/403 are on every operation without any
        // per-method annotation declaring them.
        assertThat(spec.getBody()).contains("ApiError");
        assertThat(spec.getBody()).contains("/api/v1/tasks");
    }

    @Test
    void protectedRoutesRejectAnonymousCallers() {
        assertThat(rest.getForEntity("/api/v1/tasks", String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rest.getForEntity("/api/v1/admin/tasks", String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    @Test
    void aGarbageBearerTokenIs401() {
        ResponseEntity<String> response =
                rest.exchange("/api/v1/tasks", HttpMethod.GET, bearer("not.a.real.token"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aValidUserTokenReachesTheUserRoutes() {
        assertThat(get("/api/v1/tasks", tokenFor(BOB)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAdminAlsoHoldsUserAndSoReachesTheUserRoutes() {
        // alice is [ADMIN, USER] in the realm, so @PreAuthorize("hasRole('USER')") admits her.
        assertThat(get("/api/v1/tasks", tokenFor(ALICE)).getStatusCode().value())
                .isEqualTo(200);
    }
}
