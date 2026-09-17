package com.learn.taskservice.task.service.impl;

import com.learn.taskservice.common.dto.response.PageResponse;
import com.learn.taskservice.common.exception.ResourceNotFoundException;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.mapper.TaskMapper;
import com.learn.taskservice.task.model.Task;
import com.learn.taskservice.task.model.enums.TaskStatus;
import com.learn.taskservice.task.repository.TaskRepository;
import com.learn.taskservice.task.service.AdminTaskService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin counterpart, with no ownership check anywhere in it - which is the point of it
 * being a separate class. An admin is authorized by role at the edge
 * ({@code SecurityConfig} path matcher plus {@code @PreAuthorize}), and nothing in here can
 * be reached by a USER token, so there is no per-row rule left to apply.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AdminTaskServiceImpl implements AdminTaskService {

    private final TaskRepository tasks;
    private final TaskMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(
            UUID userId, TaskStatus status, int page, int size, String sortBy, String sortDir) {
        Page<Task> result = tasks.search(userId, status, PageRequests.of(page, size, sortBy, sortDir));
        List<TaskResponse> items =
                result.getContent().stream().map(mapper::toResponse).toList();
        return PageResponse.from(result, items);
    }

    @Override
    @Transactional
    public void delete(UUID taskId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new ResourceNotFoundException("Task not found"));
        // Deleting another user's data is the one operation here worth a log line of its own:
        // the owner has no way to find out otherwise.
        log.info("Admin deleted task {} owned by user {}", taskId, task.getUserId());
        tasks.delete(task);
    }
}
