package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.taskservice.task.dto.request.UpdateTaskRequest;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * The central guarantee of this service, tested directly: a task created by one user is
 * refused - with 403, not 404 and not a silent success - to every other user, on every
 * single-task operation there is.
 *
 * <p>Each test names the status it expects rather than asserting "not 200", because the
 * difference between 403 and 404 <em>is</em> the thing under test: a 404 would be
 * indistinguishable from a query that had quietly filtered the row out, which is exactly the
 * implementation this design rejects (see {@code TaskRepository}'s Javadoc).
 */
class TaskOwnershipIT extends AbstractTaskIntegrationTest {

    private ResponseEntity<String> getTask(UUID id, String token) {
        return rest.exchange("/api/v1/tasks/" + id, HttpMethod.GET, bearer(token), String.class);
    }

    private ResponseEntity<String> putTask(UUID id, String token) {
        return rest.exchange(
                "/api/v1/tasks/" + id,
                HttpMethod.PUT,
                bearer(token, new UpdateTaskRequest("hijacked", "by carol", TaskStatus.DONE, null)),
                String.class);
    }

    private ResponseEntity<String> deleteTask(UUID id, String token) {
        return rest.exchange("/api/v1/tasks/" + id, HttpMethod.DELETE, bearer(token), String.class);
    }

    // --- the three cross-user operations, one test each ------------------------------------

    @Test
    void readingAnotherUsersTaskIs403() {
        TaskResponse bobs = createTaskAs(tokenFor(BOB), "bob's private task");

        ResponseEntity<String> response = getTask(bobs.id(), tokenFor(CAROL));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("\"error\":\"FORBIDDEN\"");
        // The refusal must not hand the intruder the very content it is refusing.
        assertThat(response.getBody()).doesNotContain("bob's private task");
    }

    @Test
    void updatingAnotherUsersTaskIs403() {
        TaskResponse bobs = createTaskAs(tokenFor(BOB), "bob's task to update");

        ResponseEntity<String> response = putTask(bobs.id(), tokenFor(CAROL));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void deletingAnotherUsersTaskIs403() {
        TaskResponse bobs = createTaskAs(tokenFor(BOB), "bob's task to delete");

        ResponseEntity<String> response = deleteTask(bobs.id(), tokenFor(CAROL));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    /**
     * A 403 that leaves the row modified or gone would be worse than a 200, so the refusals
     * above are only meaningful alongside this: the owner's task is still there, unchanged,
     * afterwards.
     */
    @Test
    void aRefusedCrossUserWriteChangesNothing() {
        String bobToken = tokenFor(BOB);
        TaskResponse bobs = createTaskAs(bobToken, "untouched");

        putTask(bobs.id(), tokenFor(CAROL));
        deleteTask(bobs.id(), tokenFor(CAROL));

        ResponseEntity<TaskResponse> reread =
                rest.exchange("/api/v1/tasks/" + bobs.id(), HttpMethod.GET, bearer(bobToken), TaskResponse.class);

        assertThat(reread.getStatusCode().value()).isEqualTo(200);
        assertThat(reread.getBody().title()).isEqualTo("untouched");
        assertThat(reread.getBody().status()).isEqualTo(TaskStatus.TODO);
    }

    /**
     * Even an ADMIN token is refused on the user-facing routes. Admin power lives on
     * {@code /api/v1/admin/tasks} and nowhere else - "admin" is not a bypass bolted onto the
     * ownership check, which is what keeps that check a single unconditional branch.
     */
    @Test
    void anAdminTokenIsAlsoRefusedOnTheUserFacingRoute() {
        TaskResponse bobs = createTaskAs(tokenFor(BOB), "bob's task, alice looking");

        assertThat(getTask(bobs.id(), tokenFor(ALICE)).getStatusCode().value()).isEqualTo(403);
        assertThat(deleteTask(bobs.id(), tokenFor(ALICE)).getStatusCode().value())
                .isEqualTo(403);
    }

    // --- the other half of the contract: 404 means "no such task, for anyone" ---------------

    /**
     * Pins the documented split. If this ever starts returning 403, the service has begun
     * confirming that an id it has never seen exists; if the tests above ever start returning
     * 404, the ownership check has been replaced by an owner-scoped query and is no longer
     * being enforced. Both halves have to be asserted for either to mean anything.
     */
    @Test
    void anIdThatExistsForNobodyIs404NotTheOwnershipRefusal() {
        ResponseEntity<String> response = getTask(UUID.randomUUID(), tokenFor(CAROL));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("\"error\":\"NOT_FOUND\"");
    }

    @Test
    void deletingAnIdThatExistsForNobodyIs404() {
        assertThat(deleteTask(UUID.randomUUID(), tokenFor(CAROL))
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    // --- and the owner themselves is never obstructed --------------------------------------

    @Test
    void theOwnerCanDoAllThreeOperationsOnTheirOwnTask() {
        String bobToken = tokenFor(BOB);
        TaskResponse bobs = createTaskAs(bobToken, "bob's own");

        assertThat(getTask(bobs.id(), bobToken).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<TaskResponse> updated = rest.exchange(
                "/api/v1/tasks/" + bobs.id(),
                HttpMethod.PUT,
                bearer(bobToken, new UpdateTaskRequest("bob's own, renamed", null, TaskStatus.DOING, null)),
                TaskResponse.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().title()).isEqualTo("bob's own, renamed");
        assertThat(updated.getBody().status()).isEqualTo(TaskStatus.DOING);
        // PUT is a full replacement: the description bob omitted is now gone, not preserved.
        assertThat(updated.getBody().description()).isNull();

        assertThat(deleteTask(bobs.id(), bobToken).getStatusCode().value()).isEqualTo(204);
        assertThat(getTask(bobs.id(), bobToken).getStatusCode().value()).isEqualTo(404);
    }
}
