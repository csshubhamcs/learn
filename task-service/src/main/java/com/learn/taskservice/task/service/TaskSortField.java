package com.learn.taskservice.task.service;

import com.learn.taskservice.common.exception.InvalidRequestException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whitelist of fields the task listings may sort by. {@code sortBy} arrives as free text, and
 * a sort field cannot be bound as a parameter the way a value can - it is spliced into the
 * query - so only these hard-coded names ever reach it. An unrecognized value throws
 * {@link InvalidRequestException}, which maps to HTTP 400; it must never be a 500, and it must
 * never be silently ignored in favour of a default, because a listing that quietly sorts by
 * something other than what was asked is worse than one that refuses.
 */
public enum TaskSortField {
    CREATED_AT("createdAt", "createdAt"),
    UPDATED_AT("updatedAt", "updatedAt"),
    DUE_DATE("dueDate", "dueDate"),
    TITLE("title", "title"),
    STATUS("status", "status");

    private final String apiName;
    private final String property;

    TaskSortField(String apiName, String property) {
        this.apiName = apiName;
        this.property = property;
    }

    /** The JPA property name, not the column name: the listings are JPQL, so Sort resolves against the entity. */
    public String property() {
        return property;
    }

    /** Case-sensitive: only the documented names are accepted, so a typo fails loudly rather than nearly working. */
    public static TaskSortField fromApiName(String value) {
        return Arrays.stream(values())
                .filter(field -> field.apiName.equals(value))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("sortBy must be one of "
                        + Arrays.stream(values()).map(f -> f.apiName).collect(Collectors.joining(", "))
                        + " but was '" + value + "'"));
    }
}
