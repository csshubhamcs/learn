package com.learn.taskservice;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts a real Keycloak with the project's realm imported, so the tests exercise real
 * signed tokens rather than a mocked JwtDecoder - which is the only way an assertion about
 * "bob's token cannot touch alice's task" means anything.
 *
 * <p>Deliberately lighter than user-service's equivalent: no kcadm bootstrap of service
 * account roles (this service is not a Keycloak client and calls no Admin API), no relaxing
 * of the realm's declarative user profile (the realm's own alice/bob/carol carry firstName
 * and lastName, so no VERIFY_PROFILE action attaches and the password grant works as
 * imported), and no mail container (nothing here sends mail).
 */
public abstract class AbstractAuthIntegrationTest extends AbstractIntegrationTest {

    protected static final String REALM = "p-platform";

    /** ADMIN + USER. */
    protected static final String ALICE = "alice@example.com";

    /** USER only - the owner in the cross-user tests. */
    protected static final String BOB = "bob@example.com";

    /** USER only - the intruder in the cross-user tests. */
    protected static final String CAROL = "carol@example.com";

    protected static final String PASSWORD = "Password123!";

    protected static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.7.3").withRealmImportFile("/realm-export.json");

    static {
        KEYCLOAK.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        // The only Keycloak property this service has. There is no client id or secret to
        // register, because it never authenticates itself to Keycloak - it only verifies
        // signatures against the realm's JWKS.
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
    }

    protected static TokenClient tokens() {
        return new TokenClient(KEYCLOAK.getAuthServerUrl(), REALM);
    }

    protected static String tokenFor(String username) {
        return tokens().passwordGrant(username, PASSWORD);
    }

    protected static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    protected static <T> HttpEntity<T> bearer(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }
}
