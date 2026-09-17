package com.learn.taskservice.task.repository;

import com.learn.taskservice.task.model.Task;
import com.learn.taskservice.task.model.enums.TaskStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for {@link Task}.
 *
 * <p>Note what is deliberately absent: there is no {@code findByIdAndUserId}. Scoping a
 * single-task lookup by owner inside the query is the tempting shortcut, and it is exactly
 * the mistake this service is built to avoid - it collapses "no such task" and "not your
 * task" into an empty Optional, so the caller cannot be told apart from a stale id and the
 * ownership rule becomes untestable. The lookup is by id alone and the owner check happens in
 * the service layer, where both outcomes still exist. See {@code TaskServiceImpl#requireOwned}.
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {

    /**
     * The owner's own listing. Filtering by {@code userId} here is not the ownership check -
     * it is the query's subject. A listing has no single resource to be forbidden from, so
     * "only rows I own" is the correct and complete answer; nothing is being hidden.
     *
     * @param status optional - null means every status
     */
    @Query("""
            select t from Task t
            where t.userId = :userId
              and (:status is null or t.status = :status)
            """)
    Page<Task> findOwnedBy(@Param("userId") UUID userId, @Param("status") TaskStatus status, Pageable pageable);

    /** The admin listing: every user's tasks, optionally narrowed to one owner and/or one status. */
    @Query("""
            select t from Task t
            where (:userId is null or t.userId = :userId)
              and (:status is null or t.status = :status)
            """)
    Page<Task> search(@Param("userId") UUID userId, @Param("status") TaskStatus status, Pageable pageable);
}
