package com.learn.taskservice.task.controller;

import com.learn.taskservice.common.dto.response.PageResponse;
import com.learn.taskservice.common.security.CurrentUser;
import com.learn.taskservice.task.dto.request.CreateTaskRequest;
import com.learn.taskservice.task.dto.request.UpdateTaskRequest;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import com.learn.taskservice.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own tasks. There is no user-id path variable or body field anywhere in
 * this controller by design: the owner comes from {@link CurrentUser}, which reads the
 * token's {@code sub} claim, so no one can create a task as somebody else or reach somebody
 * else's task by editing a URL.
 *
 * <p>The {@code {id}} routes do accept a task id from the path - they have to - and that is
 * precisely why {@code TaskService} re-checks ownership on every one of them rather than
 * trusting the route.
 */
@Tag(name = "Tasks", description = "The signed-in user's own tasks. Requires USER.")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/tasks")
@PreAuthorize("hasRole('USER')")
public class TaskController {

    private final TaskService taskService;
    private final CurrentUser currentUser;

    @Operation(
            summary = "List my tasks (page-based, filterable)",
            description = "Returns only the caller's own tasks. `sortBy` must be one of "
                    + "createdAt/updatedAt/dueDate/title/status; anything else is a 400.")
    @GetMapping
    public PageResponse<TaskResponse> list(
            @Parameter(description = "Exact status filter", example = "TODO") @RequestParam(required = false)
                    TaskStatus status,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, capped at 100", example = "20") @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(description = "One of: createdAt, updatedAt, dueDate, title, status", example = "createdAt")
                    @RequestParam(defaultValue = "createdAt")
                    String sortBy,
            @Parameter(description = "asc or desc", example = "desc") @RequestParam(defaultValue = "desc")
                    String sortDir) {
        return taskService.listOwn(currentUser.id(), status, page, size, sortBy, sortDir);
    }

    @Operation(
            summary = "Create a task",
            description = "The owner is taken from the bearer token; the payload has no user id field.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(currentUser.id(), request);
    }

    @Operation(
            summary = "Get one of my tasks",
            description = "404 if no such task exists; 403 if it exists but belongs to another user.")
    @ApiResponse(responseCode = "200", description = "The task")
    @ApiResponse(responseCode = "404", description = "No such task")
    @GetMapping("/{id}")
    public TaskResponse findById(@PathVariable UUID id) {
        return taskService.findById(id, currentUser.id());
    }

    @Operation(
            summary = "Replace one of my tasks",
            description = "Full replacement: an omitted description or dueDate is cleared, not left alone.")
    @ApiResponse(responseCode = "200", description = "The updated task")
    @ApiResponse(responseCode = "404", description = "No such task")
    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(id, currentUser.id(), request);
    }

    @Operation(summary = "Delete one of my tasks", description = "Permanent; there is no soft delete for tasks.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such task")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        taskService.delete(id, currentUser.id());
    }
}
