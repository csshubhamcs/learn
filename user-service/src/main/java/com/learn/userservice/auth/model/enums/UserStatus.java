package com.learn.userservice.auth.model.enums;

/** Lifecycle state of a local account row. */
public enum UserStatus {
    /** Can log in and use the API normally. */
    ACTIVE,
    /** Login is disabled by an admin action; the account and its data are otherwise intact. */
    SUSPENDED,
    /**
     * Soft-deleted (tombstoned). Hidden from ordinary queries by {@code User}'s
     * {@code @SQLRestriction}; its identifiers have been detached so the email is free for a
     * new registration. Not the same as being purged - the row still exists for audit/admin
     * purposes until an admin hard-deletes it.
     */
    DELETED
}
