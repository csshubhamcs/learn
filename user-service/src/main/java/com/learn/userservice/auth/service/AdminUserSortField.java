package com.learn.userservice.auth.service;

import com.learn.userservice.auth.exception.InvalidRequestException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whitelist of columns the admin listing may sort by. {@code sortBy} arrives as free text;
 * splicing it into {@code ORDER BY} would be SQL injection (a column position can't be bound
 * like a value), so only these hard-coded strings ever reach the query. An unrecognized value
 * throws {@link InvalidRequestException}, mapped to HTTP 400.
 */
public enum AdminUserSortField {
    CREATED_AT("createdAt", "created_at"),
    EMAIL("email", "email"),
    STATUS("status", "status");

    private final String apiName;
    private final String column;

    AdminUserSortField(String apiName, String column) {
        this.apiName = apiName;
        this.column = column;
    }

    public String column() {
        return column;
    }

    /** Case-sensitive: only createdAt/email/status are documented and tested. */
    public static AdminUserSortField fromApiName(String value) {
        return Arrays.stream(values())
                .filter(field -> field.apiName.equals(value))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("sortBy must be one of "
                        + Arrays.stream(values()).map(f -> f.apiName).collect(Collectors.joining(", "))
                        + " but was '" + value + "'"));
    }
}
