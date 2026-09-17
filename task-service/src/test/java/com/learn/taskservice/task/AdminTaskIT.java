package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The admin routes: everyone's tasks, and the role check that keeps a USER token off them. */
class AdminTaskIT extends AbstractTaskIntegrationTest {

    @Test
    void anAdminSeesEveryUsersTasks() {
        TaskResponse bobs = createTaskAs(tokenFor(BOB), "bob-admin-" + UUID.randomUUID());
        TaskResponse carols = createTaskAs(tokenFor(CAROL), "carol-admin-" + UUID.randomUUID());

        ResponseEntity<String> all = get("/api/v1/admin/tasks?size=100", tokenFor(ALICE));

        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(all.getBody()).contains(bobs.id().toString());
        assertThat(all.getBody()).contains(carols.id().toString());
    }

    @Test
    void filtersByUserId() {
        TaskResponse bobs = createTaskAs(tokenFor(BOB), "bob-filter-" + UUID.randomUUID());
        TaskResponse carols = createTaskAs(tokenFor(CAROL), "carol-filter-" + UUID.randomUUID());

        ResponseEntity<String> bobsOnly = get("/api/v1/admin/tasks?size=100&userId=" + bobs.userId(), tokenFor(ALICE));

        assertThat(bobsOnly.getStatusCode().value()).isEqualTo(200);
        assertThat(bobsOnly.getBody()).contains(bobs.id().toString());
        assertThat(bobsOnly.getBody()).doesNotContain(carols.id().toString());
    }

    @Test
    void filtersByStatus() {
        TaskResponse done = createTaskAs(tokenFor(BOB), "admin-done-" + UUID.randomUUID(), TaskStatus.DONE, null);
        TaskResponse todo = createTaskAs(tokenFor(BOB), "admin-todo-" + UUID.randomUUID(), TaskStatus.TODO, null);

        ResponseEntity<String> doneOnly = get("/api/v1/admin/tasks?size=100&status=DONE", tokenFor(ALICE));

        assertThat(doneOnly.getBody()).contains(done.id().toString());
        assertThat(doneOnly.getBody()).doesNotContain(todo.id().toString());
    }

    @Test
    void anAdminCanDeleteAnyUsersTask() {
        String bobToken = tokenFor(BOB);
        TaskResponse bobs = createTaskAs(bobToken, "to be admin-deleted");

        ResponseEntity<String> deleted = rest.exchange(
                "/api/v1/admin/tasks/" + bobs.id(), HttpMethod.DELETE, bearer(tokenFor(ALICE)), String.class);

        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        assertThat(rest.exchange("/api/v1/tasks/" + bobs.id(), HttpMethod.GET, bearer(bobToken), String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void deletingAnUnknownTaskAsAdminIs404() {
        assertThat(rest.exchange(
                                "/api/v1/admin/tasks/" + UUID.randomUUID(),
                                HttpMethod.DELETE,
                                bearer(tokenFor(ALICE)),
                                String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void anUnrecognizedSortByIs400OnTheAdminListingToo() {
        assertThat(get("/api/v1/admin/tasks?sortBy=whatever", tokenFor(ALICE))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }

    // --- the role boundary ------------------------------------------------------------------

    @Test
    void aUserTokenIsRejectedFromTheAdminListing() {
        ResponseEntity<String> response = get("/api/v1/admin/tasks", tokenFor(BOB));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aUserTokenIsRejectedFromTheAdminDelete() {
        TaskResponse own = createTaskAs(tokenFor(BOB), "even my own task");

        // Bob owns this task, so the refusal here is purely about the route's role
        // requirement - the admin path is not a second way to do what /api/v1/tasks does.
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/tasks/" + own.id(), HttpMethod.DELETE, bearer(tokenFor(BOB)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
