package com.masonx.paygateway.web.dto;

import java.util.List;

public record VirtualAccountPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
