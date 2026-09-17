package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.learn.userservice.AbstractIntegrationTest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.auth.service.UserService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * I1. A pooled database connection must not be held while this service is blocked on a
 * Keycloak HTTP round-trip. With {@code spring.threads.virtual.enabled=true} there is no
 * servlet-thread ceiling, so the 20-connection Hikari pool is the only concurrency limit
 * there is: a Keycloak that slows to two seconds would park every connection on remote I/O
 * and take down reads that never touch Keycloak at all.
 *
 * <p>Measured rather than asserted about: Keycloak is stubbed with an answer that samples
 * Hikari's own active-connection count at the exact moment the remote call would be in
 * flight. Because the tests call the service directly, the number is entirely this thread's -
 * no HTTP layer, no {@code open-in-view} session (it is disabled), nothing else in flight.
 */
class KeycloakOutsideTransactionIT extends AbstractIntegrationTest {

    private static final int NOT_SAMPLED = -1;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository users;

    @Autowired
    HikariDataSource dataSource;

    @MockitoBean
    KeycloakService keycloak;

    private int activeConnections() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    @Test
    void registrationHoldsNoConnectionWhileKeycloakIsCreatingTheAccount() {
        AtomicInteger heldDuringRemoteCall = new AtomicInteger(NOT_SAMPLED);
        when(keycloak.createUser(anyString(), anyString())).thenAnswer(invocation -> {
            heldDuringRemoteCall.set(activeConnections());
            return UUID.randomUUID();
        });

        userService.register(new RegisterRequest("hold-" + UUID.randomUUID() + "@example.com", "Password123!"));

        assertThat(heldDuringRemoteCall).doesNotHaveValue(NOT_SAMPLED);
        assertThat(heldDuringRemoteCall)
                .as("a Hikari connection was checked out across the Keycloak round-trip")
                .hasValue(0);
    }

    @Test
    void theEmailChangeRequestHoldsNoConnectionWhileKeycloakSendsTheMail() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail("mail-" + UUID.randomUUID() + "@example.com");
        user.setEmailNormalized(user.getEmail());
        users.saveAndFlush(user);

        AtomicInteger heldDuringRemoteCall = new AtomicInteger(NOT_SAMPLED);
        doAnswer(invocation -> {
                    heldDuringRemoteCall.set(activeConnections());
                    return null;
                })
                .when(keycloak)
                .sendUpdateEmailAction(any(UUID.class));

        userService.requestEmailChange(user.getId());

        assertThat(heldDuringRemoteCall).doesNotHaveValue(NOT_SAMPLED);
        assertThat(heldDuringRemoteCall)
                .as("a Hikari connection was checked out across an Admin API call that triggers an SMTP send")
                .hasValue(0);
    }
}
