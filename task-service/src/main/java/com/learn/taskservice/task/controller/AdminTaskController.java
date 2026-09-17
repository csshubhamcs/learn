package com.learn.taskservice.task.controller;

import com.learn.taskservice.common.dto.response.PageResponse;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import com.learn.taskservice.task.service.AdminTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administrative operations across every user's tasks. Requires the ADMIN realm role. */
@Tag(name = "Admin - tasks", description = "Operations across every user's tasks. Requires ADMIN.")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/tasks")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTaskController {

    private final AdminTaskService adminTaskService;

    @Operation(
            summary = "List all tasks (page-based, filterable)",
            description = "Every user's tasks. `userId` here is a filter an admin chooses, not an identity claim - "
                    + "it is the one place in this API a user id is read from the request.")
    @GetMapping
    public PageResponse<TaskResponse> list(
            @Parameter(description = "Exact owner filter", example = "2b1a9c44-3d5e-4f60-9a71-8c2d3e4f5061")
                    @RequestParam(required = false)
                    UUID userId,
            @Parameter(description = "Exact status filter", example = "DONE") @RequestParam(required = false)
                    TaskStatus status,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, capped at 100", example = "20") @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(description = "One of: createdAt, updatedAt, dueDate, title, status", example = "createdAt")
                    @RequestParam(defaultValue = "createdAt")
                    String sortBy,
            @Parameter(description = "asc or desc", example = "desc") @RequestParam(defaultValue = "desc")
                    String sortDir) {
        return adminTaskService.list(userId, status, page, size, sortBy, sortDir);
    }

    @Operation(summary = "Delete any task", description = "Permanent, and not restricted to the admin's own tasks.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such task")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        adminTaskService.delete(id);
    }
}
