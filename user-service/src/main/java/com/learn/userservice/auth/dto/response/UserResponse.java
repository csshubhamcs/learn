package com.learn.userservice.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Public shape of a user, returned from both self-service and admin endpoints. {@code email}
 * is resolved from {@code user_identifier} by the caller, not stored on {@code User} itself -
 * see {@code UserServiceImpl}'s class Javadoc for why the two are separate tables.
 */
public record UserResponse(
        @Schema(description = "Same UUID as the Keycloak subject id.")
        UUID id,

        @Schema(
                description = "The primary email, mirrored from Keycloak via user_identifier.",
                example = "shubham@example.com")
        String email,

        @Schema(description = "ACTIVE, SUSPENDED or DELETED.", example = "ACTIVE")
        String status,

        @Schema(description = "When the local account row was created.")
        Instant createdAt,

        @Schema(description = "Last successful login, if any tracking has recorded one.", nullable = true)
        Instant lastLoginAt) {}
