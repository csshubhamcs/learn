package com.learn.userservice.profile.model;

import com.learn.userservice.auth.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A user's profile: one row per user, every column nullable. Registration creates a user
 * with no profile data at all, and the UI fills in whatever it wants whenever it wants -
 * there is no required field and no notion of a partially-complete profile, so there is
 * nothing to model beyond "add a nullable column". A previous version of this class split
 * optional groups of fields (like the social links) into separate "section" entities with
 * their own registry, controller and service; that machinery was removed because a single
 * flat, fully-nullable table already captures the owner's model exactly and needs none of it.
 */
@Getter
@Setter
@Entity
@Table(name = "user_profile")
// Deliberately NOT @Data / @EqualsAndHashCode / @ToString - see Code conventions.
// hashCode over a mutable id breaks HashSet; toString walks lazy associations.
public class UserProfile extends BaseEntity {

    /**
     * The Keycloak subject claim. This is the primary key, not a foreign key to a separate
     * users table row via a generated id - so there is no user-to-profile mapping table
     * anywhere in the system, and looking up "my profile" is always a lookup by this id.
     */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "headline", length = 200)
    private String headline;

    @Column(name = "bio", length = 2000)
    private String bio;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 40)
    private String gender;

    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "locale", length = 10)
    private String locale;

    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "instagram_url", length = 500)
    private String instagramUrl;

    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Column(name = "x_url", length = 500)
    private String xUrl;
}
