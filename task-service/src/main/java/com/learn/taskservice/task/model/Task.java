package com.learn.taskservice.task.model;

import com.learn.taskservice.common.model.BaseEntity;
import com.learn.taskservice.task.model.enums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A single task belonging to exactly one user. */
@Getter
@Setter
@Entity
@Table(name = "task")
// Deliberately NOT @Data / @EqualsAndHashCode / @ToString - see Code conventions.
// hashCode over a mutable id breaks HashSet; toString walks lazy associations.
public class Task extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The owner: the Keycloak subject claim of the user who created this task. There is no
     * foreign key behind it - users live in user-service's database, not this one - and it is
     * {@code updatable = false} because a task never changes hands. Every read and write of
     * this field in the application comes from {@code CurrentUser#id()}; nothing anywhere
     * sets it from a request body or a path variable.
     */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    /** {@code EnumType.STRING}: an ordinal would silently remap every row if the enum ever gains a value. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status;

    /** Nullable: a task need not be scheduled, and a past date is allowed (it is then simply overdue). */
    @Column(name = "due_date")
    private LocalDate dueDate;
}
