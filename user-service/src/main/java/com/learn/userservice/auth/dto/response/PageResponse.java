package com.learn.userservice.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Page-based pagination envelope for admin listings (Problem 1). Deliberately shaped around
 * {@code page}/{@code totalPages}/{@code totalElements} rather than a next-page cursor: an
 * admin table needs page numbers and a total count to render, which a keyset cursor cannot
 * supply without a second, separately-racy counting query. Built directly from Spring Data's
 * {@code Page<T>} (see {@code AdminUserServiceImpl}), so the numbers here mean exactly what
 * they mean on that type.
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
        boolean hasPrevious) {}
