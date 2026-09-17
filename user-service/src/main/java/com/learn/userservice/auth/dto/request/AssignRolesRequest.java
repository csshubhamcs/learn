package com.learn.userservice.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Realm role names to grant in Keycloak. Roles are not mirrored into this service's own tables. */
public record AssignRolesRequest(
        @Schema(description = "Realm role names; must already exist in Keycloak.", example = "[\"USER\"]") @NotEmpty
        List<String> roles) {}
