package com.learn.userservice.auth.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * The JSON body of every error response this API returns. Registered once as an OpenAPI
 * component schema and {@code $ref}'d from every generated error response - see
 * {@code OpenApiConfig} - rather than inlined per response, so the documented shape cannot
 * drift between endpoints.
 */
@Schema(description = "Standard error body returned by every non-2xx response from this API.")
public record ApiError(
        @Schema(description = "When the error was generated.", example = "2026-09-15T10:15:30Z")
        Instant timestamp,

        @Schema(description = "The HTTP status code, repeated in the body for convenience.", example = "404")
        int status,

        @Schema(description = "Machine-readable error code - one of ErrorCode's constants.", example = "NOT_FOUND")
        String error,

        @Schema(description = "Human-readable explanation, safe to show to an end user.", example = "User not found")
        String message,

        @Schema(description = "The request path that produced this error.", example = "/api/v1/admin/users/123")
        String path,

        @Schema(
                description = "Distributed trace id; populated once OpenTelemetry is wired up, null until then.",
                example = "4bf92f3577b34da6a3ce929d0e0e4736",
                nullable = true)
        String requestId,

        @Schema(
                description = "Field name to validation message, present only for request-validation failures.",
                nullable = true)
        Map<String, String> fieldErrors) {}
