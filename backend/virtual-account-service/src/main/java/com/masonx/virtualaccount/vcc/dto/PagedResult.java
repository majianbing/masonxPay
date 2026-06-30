package com.masonx.virtualaccount.vcc.dto;

import java.util.List;

/**
 * Generic paginated response for list endpoints that can grow beyond a single page.
 */
public record PagedResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
