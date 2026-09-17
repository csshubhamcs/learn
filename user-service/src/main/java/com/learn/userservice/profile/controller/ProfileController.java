package com.learn.userservice.profile.controller;

import com.learn.userservice.auth.service.CurrentUser;
import com.learn.userservice.profile.dto.request.UpdateProfileRequest;
import com.learn.userservice.profile.dto.response.ProfileResponse;
import com.learn.userservice.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No user-id path variable by design: {@link CurrentUser} reads the id from the token's
 * {@code sub} claim, so no one can reach another user's profile by editing a URL.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users/me/profile")
@PreAuthorize("hasRole('USER')")
@Tag(name = "Profile", description = "The signed-in user's own profile")
public class ProfileController {

    private final ProfileService profileService;
    private final CurrentUser currentUser;

    @Operation(
            summary = "Get my profile",
            description = "Creates an empty profile on first read. All fields are optional and may be null. "
                    + "Returns 404 if the caller has no account in this service.")
    @GetMapping
    public ProfileResponse get() {
        return profileService.getOrCreate(currentUser.id());
    }

    @Operation(
            summary = "Update my profile",
            description = "Partial update: omitted or null fields keep their current value. "
                    + "Send an empty string to clear a field. "
                    + "Returns 404 if the caller has no account in this service.")
    @PatchMapping
    public ProfileResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        return profileService.update(currentUser.id(), request);
    }
}
