package com.learn.userservice.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * New-account request. No name fields on purpose: the realm's declarative User Profile treats
 * first/last name as optional (see {@code KeycloakServiceImpl#createUser}), and inventing a
 * placeholder name to satisfy an identity-provider policy would put fabricated data in front
 * of users - names are profile data this service's own {@code profile} module owns.
 */
public record RegisterRequest(
        @Schema(
                description = "Becomes both the Keycloak username and email in this realm.",
                example = "shubham@example.com")
        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @Schema(description = "Plaintext over TLS; never logged or echoed back.", example = "Password123!")
        @NotBlank
        @Size(min = 12, max = 128)
        String password) {}
