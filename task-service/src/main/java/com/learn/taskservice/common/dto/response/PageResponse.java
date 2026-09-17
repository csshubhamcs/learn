package com.learn.taskservice.common.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Page-based pagination envelope. Deliberately shaped around {@code page}/{@code totalPages}/
 * {@code totalElements} rather than a next-page cursor: a task list UI needs page numbers and
 * a total count to render, which a keyset cursor cannot supply without a second, separately
 * racy counting query. Built directly from Spring Data's {@code Page<T>}, so the numbers here
 * mean exactly what they mean on that type.
 *
 * @param items the rows for this page
 * @param page zero-based index of this page
 * @param size the page size that was requested (after the hard cap is applied)
 * @param totalElements total rows matching the filters, across all pages
 * @param totalPages total number of pages at this page size
 * @param hasNext whether a page after this one exists
 * @param hasPrevious whether a page before this one exists
 */
public record PageResponse<T>(
        @Schema(description = "The rows for this page.") List<T> items,

        @Schema(description = "Zero-based index of this page.", example = "0")
        int page,

        @Schema(description = "Page size actually used, after the 100 hard cap is applied.", example = "20")
        int size,

        @Schema(description = "Total rows matching the filters, across all pages.", example = "137")
        long totalElements,

        @Schema(description = "Total number of pages at this page size.", example = "7")
        int totalPages,

        @Schema(description = "Whether a page after this one exists.")
        boolean hasNext,

        @Schema(description = "Whether a page before this one exists.")
        boolean hasPrevious) {

    /**
     * Wraps an already-mapped list of DTOs in the page metadata of the entity page it came
     * from. Exists so the two listings in this service cannot drift in how they populate
     * these seven fields - the numbers must always describe the same query the items came
     * from.
     *
     * @param source the entity page the counts and flags are read from
     * @param items the entity page's content, already mapped to response DTOs. Entities are
     *     never serialised to JSON, so the mapping happens before this call, not inside it.
     */
    public static <T> PageResponse<T> from(Page<?> source, List<T> items) {
        return new PageResponse<>(
                items,
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext(),
                source.hasPrevious());
    }
}
