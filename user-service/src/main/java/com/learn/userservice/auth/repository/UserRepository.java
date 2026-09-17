package com.learn.userservice.auth.repository;

import com.learn.userservice.auth.model.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Most reads go through the inherited JpaRepository methods, which honor User's @SQLRestriction. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Honors @SQLRestriction, so a tombstoned row's old address does not block re-registration. */
    boolean existsByEmailNormalized(String emailNormalized);

    /** Native SQL, not findById: @SQLRestriction hides DELETED rows, but admins need to reach them. */
    @Query(value = "SELECT * FROM users WHERE id = :id", nativeQuery = true)
    Optional<User> findByIdIncludingDeleted(@Param("id") UUID id);

    /**
     * Backs the admin listing (page-based pagination with filters; Problem 1). Native SQL so
     * it can see DELETED rows when asked. {@code sortBy} never reaches this query as a raw
     * string - only the whitelisted column from
     * {@link com.learn.userservice.auth.service.AdminUserSortField} does, via {@code Pageable}'s
     * {@code Sort}.
     *
     * <p>Two things about the {@code q} predicate are load-bearing and must move together with
     * its caller, {@code AdminUserServiceImpl.list}:
     *
     * <ul>
     *   <li>No {@code LOWER()} wrapper on the column. {@code email_normalized} is lowercase by
     *       construction (that is what "normalized" means here - see {@code IdentifierNormalizer}
     *       and {@code softDelete}'s tombstone prefix), so the wrapper only ever cost the planner
     *       its chance to use {@code idx_users_email_normalized_trgm}: a functional expression
     *       does not match a plain-column GIN index. The caller lowercases {@code q} instead.
     *   <li>{@code ESCAPE '\'} plus a caller that has already backslash-escaped {@code %},
     *       {@code _} and {@code \} in the term. Without it {@code q=%} is a wildcard matching
     *       every row in the table, which is a one-request full scan by design accident.
     *       Postgres' default LIKE escape is already a backslash; naming it is documentation,
     *       and it does not stop the trigram index being used (verified with EXPLAIN ANALYZE).
     * </ul>
     */
    @Query(value = """
                    SELECT * FROM users u
                    WHERE (:includeDeleted = TRUE OR u.status <> 'DELETED')
                      AND (CAST(:status AS text) IS NULL OR u.status = CAST(:status AS text))
                      AND (
                        CAST(:q AS text) IS NULL
                        OR u.email_normalized LIKE CONCAT('%', CAST(:q AS text), '%') ESCAPE '\\'
                      )
                    """, countQuery = """
                    SELECT count(*) FROM users u
                    WHERE (:includeDeleted = TRUE OR u.status <> 'DELETED')
                      AND (CAST(:status AS text) IS NULL OR u.status = CAST(:status AS text))
                      AND (
                        CAST(:q AS text) IS NULL
                        OR u.email_normalized LIKE CONCAT('%', CAST(:q AS text), '%') ESCAPE '\\'
                      )
                    """, nativeQuery = true)
    Page<User> search(
            @Param("q") String normalizedQuery,
            @Param("includeDeleted") boolean includeDeleted,
            @Param("status") String status,
            Pageable pageable);

    /** Native DELETE, not deleteById: needs to reach a row @SQLRestriction would otherwise hide. */
    @Modifying
    @Query(value = "DELETE FROM users WHERE id = :id", nativeQuery = true)
    int purgeById(@Param("id") UUID id);
}
