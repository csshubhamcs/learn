package com.learn.userservice.auth.controller;

import com.learn.userservice.auth.dto.request.AssignRolesRequest;
import com.learn.userservice.auth.dto.request.UpdateStatusRequest;
import com.learn.userservice.auth.dto.response.PageResponse;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.service.AdminUserService;
import com.learn.userservice.auth.service.CurrentUser;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only operations on other users' accounts. Requires the ADMIN realm role. */
@Tag(name = "Admin - users", description = "Administrative operations on other users' accounts. Requires ADMIN.")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentUser currentUser;

    @Operation(
            summary = "List users (page-based, filterable)",
            description = "Page-based, not cursor-based, so the response carries a page number and a total. "
                    + "`sortBy` must be one of createdAt/email/status; anything else is a 400.")
    @GetMapping
    public PageResponse<UserResponse> list(
            @Parameter(description = "Partial, case-insensitive email match", example = "shubham")
                    @RequestParam(required = false)
                    String q,
            @Parameter(description = "Exact status filter", example = "ACTIVE") @RequestParam(required = false)
                    UserStatus status,
            @Parameter(description = "Include soft-deleted users") @RequestParam(defaultValue = "false")
                    boolean includeDeleted,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, capped at 100", example = "20") @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(description = "One of: createdAt, email, status", example = "createdAt")
                    @RequestParam(defaultValue = "createdAt")
                    String sortBy,
            @Parameter(description = "asc or desc", example = "desc") @RequestParam(defaultValue = "desc")
                    String sortDir) {
        return adminUserService.list(q, status, includeDeleted, page, size, sortBy, sortDir);
    }

    @Operation(
            summary = "Get a user by id",
            description = "Unlike the self-service equivalent, also returns soft-deleted users.")
    @ApiResponse(responseCode = "404", description = "No such user")
    @GetMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse findById(@PathVariable UUID id) {
        return adminUserService.findById(id);
    }

    @Operation(
            summary = "Change a user's status",
            description = "status=DELETED tombstones the account via the same path as self-delete, and is one-way.")
    @ApiResponse(responseCode = "404", description = "No such user")
    @ApiResponse(responseCode = "409", description = "The user is tombstoned and cannot be reactivated")
    @ApiResponse(responseCode = "503", description = "Keycloak unreachable")
    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    public UserResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return adminUserService.updateStatus(id, request, currentUser.id());
    }

    @Operation(
            summary = "Delete a user",
            description = "Soft-deletes by default. `hard=true` permanently erases the row and the Keycloak "
                    + "account; irreversible.")
    @ApiResponse(responseCode = "404", description = "No such user")
    @ApiResponse(responseCode = "503", description = "Keycloak unreachable")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @Parameter(description = "Permanently erase instead of soft-deleting") @RequestParam(defaultValue = "false")
                    boolean hard) {
        adminUserService.delete(id, hard, currentUser.id());
    }

    @Operation(summary = "Grant realm roles", description = "Roles are stored only in Keycloak, not mirrored locally.")
    @ApiResponse(responseCode = "400", description = "No such realm role")
    @ApiResponse(responseCode = "404", description = "No such user")
    @ApiResponse(responseCode = "503", description = "Keycloak unreachable")
    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignRoles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request) {
        adminUserService.assignRoles(id, request);
    }
}
