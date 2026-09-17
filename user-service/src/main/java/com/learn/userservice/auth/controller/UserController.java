package com.learn.userservice.auth.controller;

import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.service.CurrentUser;
import com.learn.userservice.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Self-service operations on the caller's own account, resolved from the JWT - never a path variable. */
@Tag(name = "User self-service", description = "Operations a user performs on their own account.")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('USER')")
public class UserController {

    private final UserService userService;
    private final CurrentUser currentUser;

    @Operation(summary = "Get my account", description = "Returns the caller's own record, identified from the JWT.")
    @GetMapping("/me")
    public UserResponse me() {
        return userService.findMe(currentUser.id());
    }

    @Operation(
            summary = "Delete my account",
            description = "Soft-deletes (tombstones) the account and frees its email for reuse.")
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe() {
        userService.deleteMe(currentUser.id());
    }

    /** No body: Keycloak's UPDATE_EMAIL flow collects and validates the new address itself. */
    @Operation(
            summary = "Request an email change",
            description =
                    "Triggers Keycloak's own UPDATE_EMAIL email. Nothing changes until the user completes it there.")
    @ApiResponse(responseCode = "503", description = "Keycloak unreachable")
    @PostMapping("/me/email/change")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestEmailChange() {
        userService.requestEmailChange(currentUser.id());
    }
}
