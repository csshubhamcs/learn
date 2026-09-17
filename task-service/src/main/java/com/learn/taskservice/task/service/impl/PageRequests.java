package com.learn.taskservice.task.service.impl;

import com.learn.taskservice.common.exception.InvalidRequestException;
import com.learn.taskservice.task.service.TaskSortField;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Turns the four raw pagination query parameters into a {@link PageRequest}. Shared by both
 * listings so the owner's list and the admin list cannot drift in how they clamp, validate or
 * sort - a difference there would be invisible until someone noticed one endpoint 500ing on
 * input the other handles.
 *
 * <p>The split in how bad input is treated is deliberate: {@code page} and {@code size} are
 * <em>clamped</em>, because "page -1" or "size 5000" has one obviously intended safe reading;
 * {@code sortBy} and {@code sortDir} are <em>rejected</em> with 400, because there is no safe
 * guess for what an unrecognized sort field meant, and quietly substituting a default would
 * return data sorted differently than the caller believes it to be.
 */
final class PageRequests {

    /**
     * Hard ceiling on page size regardless of what the caller asks for. One enormous page is
     * functionally an unpaginated dump, which defeats the point of paginating at all and can
     * pull an unbounded number of rows into memory.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {}

    static PageRequest of(int page, int size, String sortBy, String sortDir) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // The upper bound on page is not cosmetic. Spring Data JPA computes the SQL offset as
        // page * size and narrows it to an int, so a page number large enough to push that
        // product past Integer.MAX_VALUE throws InvalidDataAccessApiUsageException before the
        // query ever runs - a 500 from ordinary client input. Every page at or above this
        // bound is past the end of any table that fits the offset anyway, so clamping answers
        // with the same empty page the caller would have got.
        int maxPage = Integer.MAX_VALUE / safeSize;
        int safePage = Math.min(Math.max(page, 0), maxPage);

        TaskSortField sortField = TaskSortField.fromApiName(sortBy);
        Sort.Direction direction = parseDirection(sortDir);

        // A secondary sort on the primary key. Without it, two rows with the same title (or
        // the same status, or the same null due date) have no defined order between them, so
        // a row can appear on both page 1 and page 2 of the same walk, or on neither.
        Sort sort = Sort.by(direction, sortField.property()).and(Sort.by(Sort.Direction.ASC, "id"));
        return PageRequest.of(safePage, safeSize, sort);
    }

    /** Spring's own parser signals a bad value with IllegalArgumentException, which is deliberately not a 400. */
    private static Sort.Direction parseDirection(String sortDir) {
        try {
            return Sort.Direction.fromString(sortDir);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("sortDir must be asc or desc but was '" + sortDir + "'");
        }
    }
}
