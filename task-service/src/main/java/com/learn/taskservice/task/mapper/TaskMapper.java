package com.learn.taskservice.task.mapper;

import com.learn.taskservice.task.dto.request.CreateTaskRequest;
import com.learn.taskservice.task.dto.request.UpdateTaskRequest;
import com.learn.taskservice.task.dto.response.TaskResponse;
import com.learn.taskservice.task.model.Task;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between {@link Task} and its DTOs. {@code unmappedTargetPolicy = ERROR} makes the
 * build fail the moment a field is added to the entity or a DTO without a matching
 * counterpart on the other side, which is what keeps this mapper trustworthy without anyone
 * having to remember to update it by hand.
 *
 * <p>{@code id}, {@code userId} and the audit timestamps are ignored on every write mapping,
 * not merely unmapped: the id is minted by the service, the owner comes from the token, and
 * the timestamps come from JPA auditing. Ignoring them here means a request DTO can never
 * reach them even if someone later adds a same-named field to one.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaskMapper {

    /** Renders the entity for any response. */
    TaskResponse toResponse(Task task);

    /**
     * Builds a new entity from a create request. Status is set by the service afterwards so
     * the "defaults to TODO" rule lives in one place rather than being split between a mapper
     * expression and a service branch.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    Task toEntity(CreateTaskRequest request);

    /**
     * PUT semantics: full replacement. No {@code NullValuePropertyMappingStrategy.IGNORE}
     * here, unlike a PATCH mapper - a null {@code description} or {@code dueDate} in the
     * payload must actually clear the column, which is the whole difference between PUT and
     * PATCH.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void applyUpdate(UpdateTaskRequest request, @MappingTarget Task task);
}
