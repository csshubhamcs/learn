package com.learn.userservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Every value here comes from an environment variable and is validated at startup - see application.properties. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid Keycloak keycloak, @Valid Cors cors) {

    public record Keycloak(
            @NotBlank String url,
            @NotBlank String realm,
            @NotBlank String clientId,
            @NotBlank String clientSecret) {}

    public record Cors(@NotEmpty List<String> allowedOrigins) {}
}
