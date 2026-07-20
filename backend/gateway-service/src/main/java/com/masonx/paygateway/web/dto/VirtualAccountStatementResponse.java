package com.masonx.paygateway.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record VirtualAccountStatementResponse(
        boolean enabled,
        String unavailableReason,
        String ledgerAccountId,
        String asset,
        String normalBalance,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        BigDecimal netChange,
        List<VirtualAccountLedgerEntryResponse> entries
) {
    public static VirtualAccountStatementResponse unavailable(String reason) {
        return new VirtualAccountStatementResponse(false, reason, null, null, null,
                null, null, null, null, null, null, null, List.of());
    }

    public VirtualAccountStatementResponse asAvailable() {
        return new VirtualAccountStatementResponse(true, null, ledgerAccountId, asset, normalBalance,
                fromDate, toDate, openingBalance, closingBalance, totalDebits, totalCredits,
                netChange, entries);
    }
}
