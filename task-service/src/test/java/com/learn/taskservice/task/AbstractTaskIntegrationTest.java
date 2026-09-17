package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.taskservice.AbstractAuthIntegrationTest;
import com.learn.taskservice.task.dto.request.CreateTaskRequest;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.time.LocalDate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Shared fixtures for the task integration tests: creating a task as a given user, and reading one back. */
abstract class AbstractTaskIntegrationTest extends AbstractAuthIntegrationTest {

    protected TaskResponse createTaskAs(String token, String title) {
        return createTaskAs(token, title, TaskStatus.TODO, null);
    }

    protected TaskResponse createTaskAs(String token, String title, TaskStatus status, LocalDate dueDate) {
        ResponseEntity<TaskResponse> created = rest.exchange(
                "/api/v1/tasks",
                HttpMethod.POST,
                bearer(token, new CreateTaskRequest(title, "created by a test", status, dueDate)),
                TaskResponse.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return created.getBody();
    }

    protected ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, bearer(token), String.class);
    }
}
