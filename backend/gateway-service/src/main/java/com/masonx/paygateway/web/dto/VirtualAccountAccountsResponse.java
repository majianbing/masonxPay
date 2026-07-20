package com.masonx.paygateway.web.dto;

import java.util.List;

public record VirtualAccountAccountsResponse(
        boolean enabled,
        String unavailableReason,
        List<VirtualAccountLedgerAccountResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static VirtualAccountAccountsResponse available(
            VirtualAccountPageResponse<VirtualAccountLedgerAccountResponse> page) {
        return new VirtualAccountAccountsResponse(true, null, page.content(), page.page(),
                page.size(), page.totalElements(), page.totalPages());
    }

    public static VirtualAccountAccountsResponse unavailable(String reason, int page, int size) {
        return new VirtualAccountAccountsResponse(false, reason, List.of(), page, size, 0, 0);
    }
}
