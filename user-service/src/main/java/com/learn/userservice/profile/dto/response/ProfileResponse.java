package com.learn.userservice.profile.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The full profile as returned by both GET and PATCH. Any field the user has not supplied
 * yet is simply null - there is no separate "is this profile complete" flag, because the
 * owner's model has no notion of completeness, only of fields that have or haven't been set.
 */
public record ProfileResponse(
        @Schema(
                description = "Same value as the Keycloak subject claim.",
                example = "9f1c0b3e-1f2a-4c5b-8d7e-0a1b2c3d4e5f")
        UUID userId,

        @Schema(description = "Name shown to other users.", example = "Shubham Singh")
        String displayName,

        @Schema(description = "One-line tagline or job title.", example = "Backend Engineer")
        String headline,

        @Schema(description = "Longer free-text description.", example = "Building identity systems.")
        String bio,

        @Schema(description = "Absolute URL; images are not hosted here.", example = "https://cdn.example.com/a.jpg")
        String avatarUrl,

        @Schema(description = "ISO-8601; must be in the past.", example = "1996-04-12")
        LocalDate dateOfBirth,

        @Schema(description = "Free text, not an enum.", example = "male")
        String gender,

        @Schema(description = "First address line.", example = "221B Baker Street")
        String addressLine1,

        @Schema(description = "Second address line, if any.", example = "Marylebone")
        String addressLine2,

        @Schema(description = "City.", example = "Bengaluru")
        String city,

        @Schema(description = "State, province or region.", example = "Karnataka")
        String state,

        @Schema(description = "ISO 3166-1 alpha-2 code.", example = "IN")
        String country,

        @Schema(description = "Postal or ZIP code.", example = "560001")
        String postalCode,

        @Schema(description = "BCP-47 language tag.", example = "en-IN")
        String locale,

        @Schema(description = "IANA timezone name.", example = "Asia/Kolkata")
        String timezone,

        @Schema(description = "Instagram profile URL.", example = "https://instagram.com/shubham")
        String instagramUrl,

        @Schema(description = "LinkedIn profile URL.", example = "https://linkedin.com/in/shubham")
        String linkedinUrl,

        @Schema(description = "Personal or company website.", example = "https://shubhamsinghrajput.com")
        String websiteUrl,

        @Schema(description = "X (formerly Twitter) profile URL.", example = "https://x.com/shubham")
        String xUrl,

        @Schema(description = "Null until the first save.", example = "2026-09-15T10:30:00Z")
        Instant updatedAt) {}
