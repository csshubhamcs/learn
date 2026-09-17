package com.learn.userservice.auth.service;

import com.learn.userservice.auth.dto.request.AssignRolesRequest;
import com.learn.userservice.auth.dto.request.UpdateStatusRequest;
import com.learn.userservice.auth.dto.response.PageResponse;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.model.enums.UserStatus;
import java.util.UUID;

/** Administrative operations on other users' accounts. Every implementation requires ADMIN. */
public interface AdminUserService {

    /**
     * Page-based, filterable listing for an admin UI table (Problem 1). {@code sortBy} must
     * be one of the names {@link AdminUserSortField} whitelists; anything else is rejected
     * with {@code InvalidRequestException} (HTTP 400) before it ever reaches SQL.
     *
     * @param query optional case-insensitive, partial match against the user's email
     * @param status optional exact status filter
     * @param includeDeleted whether soft-deleted users are included in the results
     * @param page zero-based page index
     * @param size page size, capped at 100 regardless of what is requested
     * @param sortBy one of {@code createdAt}, {@code email}, {@code status}
     * @param sortDir {@code asc} or {@code desc}
     */
    PageResponse<UserResponse> list(
            String query, UserStatus status, boolean includeDeleted, int page, int size, String sortBy, String sortDir);

    /** Looks the user up including soft-deleted ones, unlike the self-service equivalent. */
    UserResponse findById(UUID userId);

    /**
     * Changes a user's status. Routing a status change of {@code DELETED} through the same
     * tombstoning path as self-service delete (see {@code UserServiceImpl#softDelete}) keeps
     * there being exactly one way an account becomes {@code DELETED}, admin-initiated or not.
     *
     * <p>{@code DELETED} is terminal: repeating it on an already-tombstoned user is a no-op,
     * and any other target status is refused with HTTP 409, because tombstoning releases the
     * account's email for re-registration and there is no identity left to restore.
     */
    UserResponse updateStatus(UUID userId, UpdateStatusRequest request, UUID actor);

    /**
     * Soft-deletes (tombstones, one-way, frees the email for reuse) or, if {@code hard} is
     * true, permanently erases the user's row and their Keycloak account.
     */
    void delete(UUID userId, boolean hard, UUID actor);

    /** Grants realm roles in Keycloak. Roles are not mirrored locally - see {@link KeycloakService}. */
    void assignRoles(UUID userId, AssignRolesRequest request);
}
