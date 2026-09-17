package com.learn.taskservice.task.model.enums;

/**
 * Where a task is in its life. Stored as its name, not its ordinal, so inserting a value in
 * the middle later cannot silently reinterpret every existing row.
 */
public enum TaskStatus {
    /** Not started. The status a task is created with when the caller does not choose one. */
    TODO,
    /** In progress. */
    DOING,
    /** Finished. Not terminal in any enforced sense - a task may be moved back out of it. */
    DONE
}
