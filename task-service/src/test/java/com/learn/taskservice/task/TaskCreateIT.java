package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Creation: the 201, the defaults, the validation, and - the important one - where the owner comes from. */
class TaskCreateIT extends AbstractTaskIntegrationTest {

    @Test
    void createReturns201AndTheStoredTask() {
        TaskResponse created = createTaskAs(tokenFor(BOB), "a new task", TaskStatus.DOING, LocalDate.of(2026, 12, 25));

        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("a new task");
        assertThat(created.status()).isEqualTo(TaskStatus.DOING);
        assertThat(created.dueDate()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
    }

    @Test
    void statusDefaultsToTodoWhenOmitted() {
        TaskResponse created = createTaskAs(tokenFor(BOB), "no status given", null, null);

        assertThat(created.status()).isEqualTo(TaskStatus.TODO);
    }

    /**
     * The forgery attempt, spelled out. A raw JSON body carrying a {@code userId} is sent by
     * carol; the field is not on the request record at all, so it is ignored, and the task
     * comes back owned by carol - not by the id she asked for.
     */
    @Test
    void aUserIdSmuggledIntoTheRequestBodyIsIgnoredAndTheTokenWins() {
        UUID victim = UUID.randomUUID();
        Map<String, Object> forged = Map.of(
                "title", "forged owner",
                "description", "userId in the body must not be honoured",
                "status", "TODO",
                "userId", victim.toString());

        ResponseEntity<TaskResponse> created =
                rest.exchange("/api/v1/tasks", HttpMethod.POST, bearer(tokenFor(CAROL), forged), TaskResponse.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().userId()).isNotEqualTo(victim);

        // And the created task is visible in carol's own listing, which is the positive proof
        // that the token's subject - not the body - decided ownership.
        assertThat(get("/api/v1/tasks?size=100", tokenFor(CAROL)).getBody())
                .contains(created.getBody().id().toString());
    }

    @Test
    void aBlankTitleIs400WithAFieldError() {
        Map<String, Object> invalid = Map.of("title", "   ", "status", "TODO");

        ResponseEntity<String> response =
                rest.exchange("/api/v1/tasks", HttpMethod.POST, bearer(tokenFor(BOB), invalid), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"error\":\"VALIDATION_FAILED\"");
        assertThat(response.getBody()).contains("title");
    }

    @Test
    void anOverlongTitleIs400() {
        Map<String, Object> invalid = Map.of("title", "x".repeat(201), "status", "TODO");

        assertThat(rest.exchange("/api/v1/tasks", HttpMethod.POST, bearer(tokenFor(BOB), invalid), String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }
}
