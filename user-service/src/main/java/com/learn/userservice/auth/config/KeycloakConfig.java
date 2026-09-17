package com.learn.userservice.auth.config;

import com.learn.userservice.config.AppProperties;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the singleton Keycloak Admin API client used by {@code KeycloakServiceImpl}. */
@Configuration
public class KeycloakConfig {

    /**
     * Client-credentials grant, not a user's own token: this client authenticates as the
     * {@code user-service} service account (see its {@code manage-users}/{@code view-users}
     * realm-management roles), independent of whichever end user's request triggered the call.
     */
    @Bean(destroyMethod = "close")
    Keycloak keycloakAdminClient(AppProperties properties) {
        AppProperties.Keycloak keycloak = properties.keycloak();
        return KeycloakBuilder.builder()
                .serverUrl(keycloak.url())
                .realm(keycloak.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(keycloak.clientId())
                .clientSecret(keycloak.clientSecret())
                .build();
    }
}
