package com.learn.userservice.auth.controller;

import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated entry point to the system - registration is the only endpoint anyone can call with no token. */
@Tag(name = "Authentication", description = "Unauthenticated account creation.")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;

    // DELIBERATELY UNTHROTTLED, for now. This route is permitAll (it has to be - it is how an
    // account comes into existence) and every call creates a Keycloak account and a database
    // row, so with no throttle it is a resource-exhaustion and mail-abuse vector that costs the
    // caller nothing. The in-process per-IP limiter that used to live here was removed at the
    // owner's request: it was single-node by construction (heap-resident buckets, so N replicas
    // permit N times the rate) and the real answer is a throttle in front of the service. Rate
    // limiting is to be applied at the ingress / API gateway layer when this is deployed. Known
    // and chosen for local development - not an oversight, and not safe for public traffic.
    @Operation(
            summary = "Register a new account",
            description = "Creates the Keycloak user, then the local row. "
                    + "The Keycloak user is removed again if the local write fails.")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    @ApiResponse(responseCode = "503", description = "Keycloak unreachable")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }
}
