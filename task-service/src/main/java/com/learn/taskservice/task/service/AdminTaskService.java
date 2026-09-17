package com.learn.taskservice.task.service;

import com.learn.taskservice.common.dto.response.PageResponse;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.util.UUID;

/**
 * Administrative access to every user's tasks. Requires the ADMIN realm role, enforced both
 * by the path matcher in {@code SecurityConfig} and by {@code @PreAuthorize} on the
 * controller.
 *
 * <p>No method here takes a caller id, because an admin is not scoped to an owner - that is
 * the entire difference from {@link TaskService}, and keeping the two behind separate
 * interfaces is what stops an ownership check from being accidentally skipped on the
 * user-facing path by passing a flag.
 */
public interface AdminTaskService {

    /**
     * Every task, page-based, optionally narrowed by owner and/or status.
     *
     * @param userId optional exact owner filter; null means every user
     * @param status optional exact status filter; null means every status
     * @param sortBy one of the names {@link TaskSortField} whitelists; anything else is 400
     */
    PageResponse<TaskResponse> list(UUID userId, TaskStatus status, int page, int size, String sortBy, String sortDir);

    /** Deletes any task regardless of owner. 404 if no such task; there is no 403 case for an admin. */
    void delete(UUID taskId);
}
