package com.learn.userservice.profile.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.hibernate.validator.constraints.URL;

/**
 * The PATCH payload for {@code /api/v1/users/me/profile}. Every field is nullable, and a
 * null field means "leave unchanged" - there is no separate "clear this field" signal, so
 * clearing a value currently requires setting it to blank rather than null. Validation
 * lives here rather than on the entity: this is the one place a caller-supplied value
 * enters the system, so it is also the one place that needs to reject a bad one.
 */
public record UpdateProfileRequest(
        @Schema(description = "Name shown to other users.", example = "Shubham Singh") @Size(max = 120)
        String displayName,

        @Schema(description = "One-line tagline or job title.", example = "Backend Engineer") @Size(max = 200)
        String headline,

        @Schema(description = "Longer free-text description.", example = "Building identity systems.") @Size(max = 2000)
        String bio,

        @Schema(description = "Absolute URL; images are not hosted here.", example = "https://cdn.example.com/a.jpg")
        @Size(max = 500)
        String avatarUrl,

        @Schema(description = "ISO-8601; must be in the past.", example = "1996-04-12") @Past
        LocalDate dateOfBirth,

        @Schema(description = "Free text, not an enum.", example = "male") @Size(max = 40)
        String gender,

        @Schema(description = "First address line.", example = "221B Baker Street") @Size(max = 200)
        String addressLine1,

        @Schema(description = "Second address line, if any.", example = "Marylebone") @Size(max = 200)
        String addressLine2,

        @Schema(description = "City.", example = "Bengaluru") @Size(max = 120)
        String city,

        @Schema(description = "State, province or region.", example = "Karnataka") @Size(max = 120)
        String state,

        // @Size(min = 2, max = 2) would be the obvious annotation, and it was -- but it makes
        // the field impossible to clear. A null means "leave unchanged" here, so blank is the
        // only way to erase a value, and blank is not two characters. The alternation allows
        // exactly that one extra case, and tightens the rest to letters while it is here: the
        // field is documented as ISO 3166-1 alpha-2, which "12" never was.
        @Schema(description = "ISO 3166-1 alpha-2 code, or blank to clear.", example = "IN")
        @Pattern(regexp = "^$|^[A-Za-z]{2}$", message = "must be a 2-letter ISO 3166-1 country code, or blank")
        String country,

        @Schema(description = "Postal or ZIP code.", example = "560001") @Size(max = 20)
        String postalCode,

        @Schema(description = "BCP-47 language tag.", example = "en-IN") @Size(max = 10)
        String locale,

        @Schema(description = "IANA timezone name.", example = "Asia/Kolkata") @Size(max = 64)
        String timezone,

        @Schema(description = "Instagram profile URL.", example = "https://instagram.com/shubham") @URL @Size(max = 500)
        String instagramUrl,

        @Schema(description = "LinkedIn profile URL.", example = "https://linkedin.com/in/shubham")
        @URL
        @Size(max = 500)
        String linkedinUrl,

        @Schema(description = "Personal or company website.", example = "https://shubhamsinghrajput.com")
        @URL
        @Size(max = 500)
        String websiteUrl,

        @Schema(description = "X (formerly Twitter) profile URL.", example = "https://x.com/shubham")
        @URL
        @Size(max = 500)
        String xUrl) {}
