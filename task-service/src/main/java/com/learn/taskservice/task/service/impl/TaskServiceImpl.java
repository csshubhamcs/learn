package com.learn.taskservice.task.service.impl;

import com.learn.taskservice.common.dto.response.PageResponse;
import com.learn.taskservice.common.exception.NotResourceOwnerException;
import com.learn.taskservice.common.exception.ResourceNotFoundException;
import com.learn.taskservice.task.dto.request.CreateTaskRequest;
import com.learn.taskservice.task.dto.request.UpdateTaskRequest;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.mapper.TaskMapper;
import com.learn.taskservice.task.model.Task;
import com.learn.taskservice.task.model.enums.TaskStatus;
import com.learn.taskservice.task.repository.TaskRepository;
import com.learn.taskservice.task.service.TaskService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service layer is where ownership is enforced, on purpose, and it is the only place it
 * is enforced. Not in the controller, which would leave the rule one new endpoint away from
 * being forgotten; not in a repository query scoped by {@code userId}, which would answer
 * "not found" to a request that should be refused and make the rule impossible to test apart
 * from an ordinary miss.
 *
 * <p>{@link #requireOwned} is the single gate every single-task operation passes through.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TaskServiceImpl implements TaskService {

    private final TaskRepository tasks;
    private final TaskMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> listOwn(
            UUID userId, TaskStatus status, int page, int size, String sortBy, String sortDir) {
        Page<Task> result = tasks.findOwnedBy(userId, status, PageRequests.of(page, size, sortBy, sortDir));
        return PageResponse.from(result, toResponses(result));
    }

    @Override
    @Transactional
    public TaskResponse create(UUID userId, CreateTaskRequest request) {
        Task task = mapper.toEntity(request);
        task.setId(UUID.randomUUID());
        // The one and only place a task's owner is ever assigned, and it comes from the
        // caller's token by way of the controller. CreateTaskRequest has no owner field to
        // read even if someone tried.
        task.setUserId(userId);
        task.setStatus(request.status() == null ? TaskStatus.TODO : request.status());
        return mapper.toResponse(tasks.save(task));
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse findById(UUID taskId, UUID userId) {
        return mapper.toResponse(requireOwned(taskId, userId));
    }

    @Override
    @Transactional
    public TaskResponse update(UUID taskId, UUID userId, UpdateTaskRequest request) {
        Task task = requireOwned(taskId, userId);
        mapper.applyUpdate(request, task);
        return mapper.toResponse(tasks.save(task));
    }

    @Override
    @Transactional
    public void delete(UUID taskId, UUID userId) {
        tasks.delete(requireOwned(taskId, userId));
    }

    /**
     * Loads a task by id alone and then refuses it if the caller does not own it. The two
     * steps are separate on purpose, and the order matters:
     *
     * <ol>
     *   <li>No row at all - nobody's task - is 404.
     *   <li>A row owned by someone else is 403, not 404 and emphatically not a silently empty
     *       result. See {@link NotResourceOwnerException} for why this service accepts the
     *       existence leak that choice implies.
     * </ol>
     *
     * <p>Looking the row up unscoped is what makes both answers reachable. A
     * {@code findByIdAndUserId} would fold case 2 into case 1 and there would be nothing left
     * to assert in a test but "the row I do not own is not visible" - which is also what a
     * broken check looks like.
     */
    private Task requireOwned(UUID taskId, UUID callerId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        if (!task.getUserId().equals(callerId)) {
            // Both ids are opaque user/task identifiers, never token material. Logged at WARN
            // because a cross-user attempt is either a client bug worth finding or an attack
            // worth seeing.
            log.warn("Ownership check refused user {} access to task {}", callerId, taskId);
            throw new NotResourceOwnerException("This task belongs to another user");
        }
        return task;
    }

    private List<TaskResponse> toResponses(Page<Task> page) {
        return page.getContent().stream().map(mapper::toResponse).toList();
    }
}
