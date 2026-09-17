package com.learn.taskservice.task.dto.request;

import com.learn.taskservice.task.model.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The POST payload for {@code /api/v1/tasks}. There is deliberately no {@code userId} field:
 * the owner is taken from the caller's token, so there is nothing here for a client to
 * supply - or to forge.
 */
public record CreateTaskRequest(
        @Schema(description = "Short summary of the task.", example = "Write the migration guide")
        @NotBlank
        @Size(max = 200)
        String title,

        @Schema(description = "Optional longer description.", example = "Cover the Boot 3 to Boot 4 relocations.")
        @Size(max = 2000)
        String description,

        @Schema(description = "Defaults to TODO when omitted.", example = "TODO", nullable = true)
        TaskStatus status,

        @Schema(
                description = "Optional ISO-8601 due date. A past date is allowed - it is then overdue.",
                example = "2026-10-01",
                nullable = true)
        LocalDate dueDate) {}
