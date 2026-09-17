package com.learn.taskservice.task.service;

import com.learn.taskservice.common.dto.response.PageResponse;
import com.learn.taskservice.task.dto.request.CreateTaskRequest;
import com.learn.taskservice.task.dto.request.UpdateTaskRequest;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.util.UUID;

/**
 * The signed-in user's own tasks. Every method takes the caller's id as an explicit
 * parameter, supplied by the controller from {@code CurrentUser} - never from a path variable
 * or a request body - and every single-task method enforces ownership before doing anything
 * else.
 *
 * <h2>The status contract, in one place</h2>
 *
 * <ul>
 *   <li><strong>404</strong> - no task with that id exists, for anybody.
 *   <li><strong>403</strong> - the task exists and belongs to a different user.
 * </ul>
 *
 * The reasoning for splitting them this way, rather than answering 404 to both, is written
 * out in {@code NotResourceOwnerException}'s Javadoc. It applies identically to
 * {@link #findById}, {@link #update} and {@link #delete}: there is no operation on someone
 * else's task that succeeds, and none that pretends the task is missing.
 */
public interface TaskService {

    /**
     * The caller's own tasks, page-based. Nothing is hidden here and nothing is forbidden -
     * a listing has no single resource to be denied, so scoping the query to the caller is
     * the complete answer, not an ownership check standing in for one.
     *
     * @param userId the caller, from the token's {@code sub} claim
     * @param status optional exact status filter; null means every status
     * @param page zero-based page index, clamped to a safe range
     * @param size page size, capped at 100 regardless of what is requested
     * @param sortBy one of the names {@link TaskSortField} whitelists; anything else is 400
     * @param sortDir {@code asc} or {@code desc}; anything else is 400
     */
    PageResponse<TaskResponse> listOwn(
            UUID userId, TaskStatus status, int page, int size, String sortBy, String sortDir);

    /** Creates a task owned by {@code userId}. The request DTO carries no owner field at all. */
    TaskResponse create(UUID userId, CreateTaskRequest request);

    /** @throws com.learn.taskservice.common.exception.NotResourceOwnerException if the task is another user's */
    TaskResponse findById(UUID taskId, UUID userId);

    /** Full replacement. @throws com.learn.taskservice.common.exception.NotResourceOwnerException if not the owner */
    TaskResponse update(UUID taskId, UUID userId, UpdateTaskRequest request);

    /** @throws com.learn.taskservice.common.exception.NotResourceOwnerException if the task is another user's */
    void delete(UUID taskId, UUID userId);
}
