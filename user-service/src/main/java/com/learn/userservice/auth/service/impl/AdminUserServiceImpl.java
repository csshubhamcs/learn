package com.learn.userservice.auth.service.impl;

import com.learn.userservice.auth.dto.request.AssignRolesRequest;
import com.learn.userservice.auth.dto.request.UpdateStatusRequest;
import com.learn.userservice.auth.dto.response.PageResponse;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.exception.InvalidRequestException;
import com.learn.userservice.auth.exception.InvalidStateTransitionException;
import com.learn.userservice.auth.exception.ResourceNotFoundException;
import com.learn.userservice.auth.mapper.UserMapper;
import com.learn.userservice.auth.model.User;
import com.learn.userservice.auth.model.enums.UserStatus;
import com.learn.userservice.auth.repository.UserRepository;
import com.learn.userservice.auth.service.AdminUserService;
import com.learn.userservice.auth.service.AdminUserSortField;
import com.learn.userservice.auth.service.KeycloakService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Injects the concrete {@link UserServiceImpl}, not the {@code UserService} interface, so it
 * can reuse the package-private {@code softDelete} helper instead of duplicating tombstoning
 * logic. A deliberate decision, not an oversight: it keeps "how a user gets tombstoned"
 * defined in exactly one place, whichever controller triggered it.
 */
@RequiredArgsConstructor
@Service
public class AdminUserServiceImpl implements AdminUserService {

    /**
     * Hard ceiling on page size regardless of what the caller asks for. An admin table asking
     * for one enormous page is functionally a full unpaginated dump, which defeats the point
     * of paginating at all and can pull an unbounded number of rows into memory.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository users;
    private final UserMapper mapper;
    private final KeycloakService keycloak;
    private final UserServiceImpl userService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(
            String query,
            UserStatus status,
            boolean includeDeleted,
            int page,
            int size,
            String sortBy,
            String sortDir) {
        // Never trust page/size from the caller to be sane; clamp rather than reject, since
        // "page -1" or "size 5000" has one obviously-intended safe interpretation.
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // The upper bound on page is not cosmetic. Spring Data JPA computes the SQL offset as
        // page * size and narrows it to an int (PageableUtils.getOffsetAsInteger), so a page
        // number large enough to push that product past Integer.MAX_VALUE threw
        // InvalidDataAccessApiUsageException before the query ever ran - a 500 from ordinary
        // client input, which is exactly what GlobalExceptionHandler exists to prevent. Every
        // page at or above this bound is past the end of any table that fits the offset
        // anyway, so clamping answers with the same empty page the caller would have got.
        int maxPage = Integer.MAX_VALUE / safeSize;
        int safePage = Math.min(Math.max(page, 0), maxPage);

        // Both reject bad input with InvalidRequestException (HTTP 400). See
        // AdminUserSortField's Javadoc for why sortBy in particular must be resolved through
        // a whitelist rather than passed to SQL as-is.
        AdminUserSortField sortField = AdminUserSortField.fromApiName(sortBy);
        Sort.Direction direction = parseDirection(sortDir);

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, sortField.column()));
        String normalizedQuery = (query == null || query.isBlank())
                ? null
                : escapeLikeWildcards(query.trim().toLowerCase(Locale.ROOT));
        String statusFilter = status == null ? null : status.name();

        Page<User> result = users.search(normalizedQuery, includeDeleted, statusFilter, pageable);

        List<UserResponse> items =
                result.getContent().stream().map(mapper::toResponse).toList();

        return new PageResponse<>(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findById(UUID userId) {
        return mapper.toResponse(requireUser(userId));
    }

    @Override
    @Transactional
    public UserResponse updateStatus(UUID userId, UpdateStatusRequest request, UUID actor) {
        User user = requireUser(userId);

        if (user.getStatus() == UserStatus.DELETED) {
            // Re-tombstoning an already-tombstoned user would prepend a second
            // "deleted:<uuid>:" to email_normalized, rename Keycloak to a fresh tombstone
            // address and overwrite deletedAt/deletedBy - losing the record of who really
            // deleted the account and, after a few calls, overflowing varchar(320).
            if (request.status() == UserStatus.DELETED) {
                return mapper.toResponse(user);
            }
            // Tombstoning is one-way by design: softDelete detaches the address in both
            // systems precisely so it can be registered again, so there is no identity left
            // to give back and the old address may already belong to somebody else. Refusing
            // is the only answer that does not report a healthy account nobody can log in to.
            throw new InvalidStateTransitionException(
                    "A deleted user cannot be reactivated: the account is tombstoned and its email has been released");
        }

        if (request.status() == UserStatus.DELETED) {
            userService.softDelete(user, actor);
            return mapper.toResponse(user);
        }

        user.setStatus(request.status());
        users.save(user);
        keycloak.setEnabled(userId, request.status() == UserStatus.ACTIVE);
        return mapper.toResponse(user);
    }

    @Override
    @Transactional
    public void delete(UUID userId, boolean hard, UUID actor) {
        User user = requireUser(userId);

        if (!hard) {
            if (user.getStatus() != UserStatus.DELETED) {
                userService.softDelete(user, actor);
            }
            return;
        }

        // Keycloak first: if it fails the transaction rolls back and the row survives,
        // which is the recoverable ordering. The reverse would strand a login with no profile.
        keycloak.deleteUser(userId);
        users.purgeById(userId);
    }

    @Override
    public void assignRoles(UUID userId, AssignRolesRequest request) {
        requireUser(userId);
        keycloak.assignRealmRoles(userId, request.roles());
    }

    /**
     * Makes the caller's search term a literal. A bound parameter stops SQL injection but not
     * LIKE's own metacharacters: {@code q=%} is a wildcard that matches every row in the table
     * and {@code q=_} matches any single character, so an admin searching for an address that
     * happens to contain an underscore got silently wrong results, and one typo'd {@code %}
     * scanned a million rows.
     *
     * <p>The backslash must be doubled first, or escaping {@code %} would then re-escape its
     * own backslash. Pairs with {@code ESCAPE '\'} in {@link UserRepository#search} - change
     * one and the other stops meaning anything.
     */
    private static String escapeLikeWildcards(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Spring's own parser signals a bad value with IllegalArgumentException, which is no longer a 400 by itself. */
    private static Sort.Direction parseDirection(String sortDir) {
        try {
            return Sort.Direction.fromString(sortDir);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("sortDir must be asc or desc but was '" + sortDir + "'");
        }
    }

    /** Uses the including-deleted lookup so admins can act on soft-deleted users. */
    private User requireUser(UUID userId) {
        return users.findByIdIncludingDeleted(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
