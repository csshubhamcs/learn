package com.learn.taskservice.task.dto.request;

import com.learn.taskservice.task.model.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The PUT payload for {@code /api/v1/tasks/{id}}. PUT, not PATCH, so this is a full
 * replacement: {@code title} and {@code status} are required, and omitting
 * {@code description} or {@code dueDate} clears them rather than leaving them alone. That is
 * what makes the verb honest - a client that sends back a task it read, minus a field, means
 * to remove that field.
 *
 * <p>Carries no {@code userId} for the same reason {@link CreateTaskRequest} does not: a task
 * never changes hands, and the owner is not a client-supplied value.
 */
public record UpdateTaskRequest(
        @Schema(description = "Short summary of the task.", example = "Write the migration guide")
        @NotBlank
        @Size(max = 200)
        String title,

        @Schema(description = "Longer description. Omit or null to clear it.", example = "Cover the relocations.")
        @Size(max = 2000)
        String description,

        @Schema(description = "Required - PUT replaces the whole task.", example = "DOING") @NotNull
        TaskStatus status,

        @Schema(description = "Due date. Omit or null to clear it.", example = "2026-10-01", nullable = true)
        LocalDate dueDate) {}
