package com.learn.taskservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every value here comes from an environment variable and is validated at startup - see
 * application.properties. Deliberately carries no Keycloak client credentials: this service
 * only ever verifies JWT signatures against the realm's JWKS, so it has nothing to
 * authenticate itself as.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid Cors cors) {

    public record Cors(@NotEmpty List<String> allowedOrigins) {}
}
