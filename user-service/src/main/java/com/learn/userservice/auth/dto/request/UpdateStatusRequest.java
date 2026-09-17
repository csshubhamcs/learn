package com.learn.userservice.auth.dto.request;

import com.learn.userservice.auth.model.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Admin request to change a user's status. DELETED routes through the same tombstoning path as self-delete. */
public record UpdateStatusRequest(
        @Schema(description = "New status; DELETED tombstones the account.", example = "SUSPENDED") @NotNull
        UserStatus status) {}
