package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** The owner's own listing: what it contains, what it excludes, and how it refuses bad input. */
class TaskListIT extends AbstractTaskIntegrationTest {

    @Test
    void aUserSeesOnlyTheirOwnTasks() {
        String marker = UUID.randomUUID().toString();
        String bobToken = tokenFor(BOB);
        String carolToken = tokenFor(CAROL);

        TaskResponse bobs = createTaskAs(bobToken, "bob-" + marker);
        TaskResponse carols = createTaskAs(carolToken, "carol-" + marker);

        ResponseEntity<String> bobsList = get("/api/v1/tasks?size=100", bobToken);

        assertThat(bobsList.getStatusCode().value()).isEqualTo(200);
        assertThat(bobsList.getBody()).contains(bobs.id().toString());
        assertThat(bobsList.getBody()).doesNotContain(carols.id().toString());

        ResponseEntity<String> carolsList = get("/api/v1/tasks?size=100", carolToken);
        assertThat(carolsList.getBody()).contains(carols.id().toString());
        assertThat(carolsList.getBody()).doesNotContain(bobs.id().toString());
    }

    @Test
    void theListingIsPagedWithATotalAndNavigationFlags() {
        String bobToken = tokenFor(BOB);
        for (int i = 0; i < 3; i++) {
            createTaskAs(bobToken, "paged-" + UUID.randomUUID());
        }

        ResponseEntity<String> firstPage = get("/api/v1/tasks?size=2&page=0", bobToken);

        assertThat(firstPage.getStatusCode().value()).isEqualTo(200);
        assertThat(firstPage.getBody()).contains("\"page\":0");
        assertThat(firstPage.getBody()).contains("\"size\":2");
        assertThat(firstPage.getBody()).contains("\"hasPrevious\":false");
        assertThat(firstPage.getBody()).contains("\"totalElements\":");

        ResponseEntity<String> secondPage = get("/api/v1/tasks?size=2&page=1", bobToken);
        assertThat(secondPage.getBody()).contains("\"page\":1");
        assertThat(secondPage.getBody()).contains("\"hasPrevious\":true");
    }

    @Test
    void filtersByStatus() {
        String carolToken = tokenFor(CAROL);
        TaskResponse todo = createTaskAs(carolToken, "todo-" + UUID.randomUUID(), TaskStatus.TODO, null);
        TaskResponse done =
                createTaskAs(carolToken, "done-" + UUID.randomUUID(), TaskStatus.DONE, LocalDate.of(2026, 10, 1));

        ResponseEntity<String> doneOnly = get("/api/v1/tasks?status=DONE&size=100", carolToken);

        assertThat(doneOnly.getStatusCode().value()).isEqualTo(200);
        assertThat(doneOnly.getBody()).contains(done.id().toString());
        assertThat(doneOnly.getBody()).doesNotContain(todo.id().toString());
    }

    /**
     * The whole point of the whitelist. Splicing an arbitrary string into the sort would
     * either be an injection vector or, at best, a 500 from ordinary client input.
     */
    @Test
    void anUnrecognizedSortByIs400NotA500() {
        ResponseEntity<String> response = get("/api/v1/tasks?sortBy=;drop%20table%20task", tokenFor(BOB));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"error\":\"VALIDATION_FAILED\"");
        assertThat(response.getBody()).contains("sortBy must be one of");
    }

    @Test
    void aPlausibleButUnknownSortFieldIsAlso400() {
        assertThat(get("/api/v1/tasks?sortBy=userId", tokenFor(BOB))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }

    @Test
    void anUnrecognizedSortDirIs400() {
        ResponseEntity<String> response = get("/api/v1/tasks?sortDir=sideways", tokenFor(BOB));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("sortDir must be asc or desc");
    }

    /** An unparseable enum in a query parameter is client input, so 400 - never a 500 from the type converter. */
    @Test
    void anUnrecognizedStatusFilterIs400() {
        assertThat(get("/api/v1/tasks?status=NOT_A_STATUS", tokenFor(BOB))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }

    /** Nonsense page/size is clamped rather than rejected: there is one obvious safe reading of each. */
    @Test
    void absurdPagingIsClampedNotRejected() {
        ResponseEntity<String> response = get("/api/v1/tasks?page=-5&size=100000", tokenFor(BOB));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"page\":0");
        assertThat(response.getBody()).contains("\"size\":100");
    }

    @Test
    void sortingByTheWhitelistedFieldsWorks() {
        String bobToken = tokenFor(BOB);
        for (String sortBy : new String[] {"createdAt", "updatedAt", "dueDate", "title", "status"}) {
            assertThat(get("/api/v1/tasks?sortBy=" + sortBy + "&sortDir=asc", bobToken)
                            .getStatusCode()
                            .value())
                    .as("sortBy=%s", sortBy)
                    .isEqualTo(200);
        }
    }
}
