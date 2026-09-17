package com.learn.taskservice.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learn.taskservice.common.exception.ErrorCode;
import com.learn.taskservice.common.exception.InvalidRequestException;
import com.learn.taskservice.task.service.TaskSortField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A plain unit test - no container - for the whitelist the listings' 400 depends on. */
class TaskSortFieldTest {

    @ParameterizedTest
    @ValueSource(strings = {"createdAt", "updatedAt", "dueDate", "title", "status"})
    void acceptsEveryDocumentedName(String apiName) {
        assertThat(TaskSortField.fromApiName(apiName).property()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "id", "CREATED_AT", "createdat", "", "created_at; drop table task"})
    void rejectsAnythingElseWithA400Code(String apiName) {
        assertThatThrownBy(() -> TaskSortField.fromApiName(apiName))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e ->
                        assertThat(((InvalidRequestException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> TaskSortField.fromApiName(null)).isInstanceOf(InvalidRequestException.class);
    }

    /**
     * The message names the legal values. An error that only says "invalid" forces the caller
     * to go read the source, and this one is safe to echo back - it contains no internals.
     */
    @Test
    void theMessageListsTheAcceptedValues() {
        assertThatThrownBy(() -> TaskSortField.fromApiName("nope"))
                .hasMessageContaining("createdAt")
                .hasMessageContaining("dueDate")
                .hasMessageContaining("nope");
    }
}
