package com.learn.taskservice.task.dto.response;

import com.learn.taskservice.task.model.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A task as returned by every endpoint in this service. The {@code Task} entity itself is
 * never serialised to JSON; this record is the only shape that reaches a client, so adding a
 * column to the entity cannot accidentally publish it.
 */
public record TaskResponse(
        @Schema(description = "The task's id.", example = "9f1c0b3e-1f2a-4c5b-8d7e-0a1b2c3d4e5f")
        UUID id,

        @Schema(
                description = "The owner's user id - the Keycloak subject claim. Echoed back so a client can "
                        + "confirm what the server decided; it is never read from a request.",
                example = "2b1a9c44-3d5e-4f60-9a71-8c2d3e4f5061")
        UUID userId,

        @Schema(description = "Short summary of the task.", example = "Write the migration guide")
        String title,

        @Schema(description = "Longer description, or null.", example = "Cover the relocations.", nullable = true)
        String description,

        @Schema(description = "Where the task is in its life.", example = "TODO")
        TaskStatus status,

        @Schema(description = "Due date, or null if the task is unscheduled.", example = "2026-10-01", nullable = true)
        LocalDate dueDate,

        @Schema(description = "When the task was created.", example = "2026-09-17T10:30:00Z")
        Instant createdAt,

        @Schema(description = "When the task was last modified.", example = "2026-09-17T11:05:00Z")
        Instant updatedAt) {}
