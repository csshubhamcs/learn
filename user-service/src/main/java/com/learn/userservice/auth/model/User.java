package com.learn.userservice.auth.model;

import com.learn.userservice.auth.model.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * Local account row keyed by the Keycloak subject id (same UUID, no mapping table). Keycloak
 * is the source of truth for identity, including email; {@link #email}/{@link #emailNormalized}
 * are a read model that exists only so admin search/filter/sort can run as one SQL query
 * instead of calling Keycloak's Admin API - see {@code UserServiceImpl}'s class Javadoc. Since
 * self-service email change is now Keycloak's own UPDATE_EMAIL flow (we are never called back),
 * this mirror can go briefly stale after a user changes their email directly in Keycloak; it is
 * refreshed the next time something writes this row (e.g. an admin action or a future login
 * sync), and until then admin search may show the old address.
 *
 * <p>{@code @SQLRestriction} hides soft-deleted rows from every ordinary query; the admin path
 * needs to see them too, so it goes around this via native queries in {@code UserRepository}.
 */
@Getter
@Setter
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email_normalized", columnNames = "email_normalized"))
@SQLRestriction("status <> 'DELETED'")
// Deliberately NOT @Data / @EqualsAndHashCode / @ToString - see Code conventions.
// hashCode over a mutable id breaks HashSet; toString walks lazy associations.
public class User extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    /** As entered, for display. Only {@code UserServiceImpl#writeEmail} may set this. */
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    /** Lowercased canonical form; the unique constraint is on this column, not {@link #email}. */
    @Column(name = "email_normalized", nullable = false, length = 320)
    private String emailNormalized;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;
}
