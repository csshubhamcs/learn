package com.learn.userservice;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

public abstract class AbstractAuthIntegrationTest extends AbstractIntegrationTest {

    protected static final String REALM = "p-platform";

    private static final Network NETWORK = Network.newNetwork();

    // realm-export.json points Keycloak's SMTP settings at host "mailpit" (matching the
    // docker-compose service of the same name); self-service email change now relies on
    // Keycloak actually being able to send that mail (UPDATE_EMAIL, execute-actions-email) -
    // unlike every other KeycloakService method, a failed send surfaces synchronously as an
    // Admin API error. Without a container answering to that hostname on this container's
    // network, execute-actions-email fails with "Non authenticated connect failed" /
    // UnknownHostException and the whole flow cannot be exercised at all.
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
                    DockerImageName.parse("axllent/mailpit:latest"))
            .withNetwork(NETWORK)
            .withNetworkAliases("mailpit")
            .withExposedPorts(1025, 8025);

    protected static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.7.3")
            .withRealmImportFile("/realm-export.json")
            .withNetwork(NETWORK);

    static {
        MAILPIT.start();
        KEYCLOAK.start();
        grantServiceAccountRealmManagementRoles();
    }

    // Mirrors the docker-compose `keycloak-bootstrap` one-shot container: Keycloak 26.7.3
    // rejects an unrecognized "serviceAccountClientRoles" field on ClientRepresentation and
    // aborts --import-realm entirely, so realm-export.json cannot carry these role grants
    // itself. Whoever imports the realm (docker-compose there, Testcontainers here) has to
    // grant the user-service service account its realm-management roles at runtime instead.
    //
    // Run via kcadm.sh *inside* the container (not the Java admin client from the host) -
    // in this Docker Desktop setup, requests that arrive at Keycloak from the host are seen
    // as external and rejected with "HTTPS required" once sslRequired defaults kick in for
    // the master realm (which, unlike p-platform, is not ours to edit in realm-export.json).
    // kcadm.sh talking to localhost:8080 from within the container never has that problem.
    private static void grantServiceAccountRealmManagementRoles() {
        try {
            exec(
                    "/opt/keycloak/bin/kcadm.sh",
                    "config",
                    "credentials",
                    "--server",
                    "http://localhost:8080",
                    "--realm",
                    "master",
                    "--user",
                    KEYCLOAK.getAdminUsername(),
                    "--password",
                    KEYCLOAK.getAdminPassword());
            exec(
                    "/opt/keycloak/bin/kcadm.sh",
                    "add-roles",
                    "-r",
                    REALM,
                    "--uusername",
                    "service-account-user-service",
                    "--cclientid",
                    "realm-management",
                    "--rolename",
                    "manage-users",
                    "--rolename",
                    "view-users",
                    "--rolename",
                    "query-users",
                    // view-realm: KeycloakServiceImpl.assignRealmRoles looks a role up by name
                    // (GET /admin/realms/{realm}/roles/{name}) before mapping it; that read
                    // needs view-realm even though the write itself only needs manage-users.
                    "--rolename",
                    "view-realm");

            // Mirrors the docker-compose keycloak-bootstrap container: the realm's declarative
            // User Profile marks firstName/lastName required by default, but registration
            // (KeycloakServiceImpl.createUser) intentionally sets neither. An unmet requirement
            // silently attaches a dynamic VERIFY_PROFILE action that blocks the very first
            // password grant with "Account is not fully set up" - invisible via GET on the
            // user's requiredActions, which makes it very hard to diagnose from a failing test
            // alone. realm-export.json cannot carry the relaxed profile itself here any more
            // than it can carry service account role grants, so it is applied at runtime, the
            // same way the docker-compose bootstrap container applies it against a real cluster.
            //
            // kcadm pretty-prints each "required" block across three lines
            // (`"required" : {` / `"roles" : [ "user" ]` / `},`), so a single-line sed pattern
            // never matches and silently leaves the requirement in place - verified directly
            // against a live container's `get users/profile` output. `sed -z` treats the whole
            // file as one record so the pattern can span those newlines.
            exec(
                    "/bin/bash",
                    "-c",
                    "/opt/keycloak/bin/kcadm.sh get users/profile -r " + REALM + " > /tmp/up.json && "
                            + "sed -z -e 's/\"required\" : {[^}]*},\\n*//g' /tmp/up.json > /tmp/up2.json && "
                            + "/opt/keycloak/bin/kcadm.sh update users/profile -r " + REALM + " -f /tmp/up2.json");

            // Mirrors the docker-compose keycloak-bootstrap container: self-service email
            // change is Keycloak's own UPDATE_EMAIL required action, disabled by default in
            // this realm.
            exec(
                    "/opt/keycloak/bin/kcadm.sh",
                    "update",
                    "authentication/required-actions/UPDATE_EMAIL",
                    "-r",
                    REALM,
                    "-s",
                    "enabled=true");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap Keycloak service account roles", e);
        }
    }

    private static void exec(String... command) throws Exception {
        org.testcontainers.containers.Container.ExecResult result = KEYCLOAK.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("kcadm command failed: " + result.getStdout() + result.getStderr());
        }
    }

    @Autowired
    protected TestRestTemplate rest;

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("app.keycloak.url", KEYCLOAK::getAuthServerUrl);
        registry.add("app.keycloak.realm", () -> REALM);
        registry.add("app.keycloak.client-id", () -> "user-service");
        registry.add("app.keycloak.client-secret", () -> "user-service-secret");
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
    }

    protected static TokenClient tokens() {
        return new TokenClient(KEYCLOAK.getAuthServerUrl(), REALM);
    }

    /** Mailpit's own HTTP API, for tests that need to prove a mail was actually produced. */
    protected static String mailpitApiUrl() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages";
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
