package com.masonx.virtualaccount.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AccountStatementResponse(
        String ledgerAccountId,
        String asset,
        String normalBalance,    // "DEBIT" or "CREDIT" — caller needs this to interpret sign
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingBalance,  // signed balance at effective_date < fromDate
        BigDecimal closingBalance,  // signed balance at effective_date <= toDate
        BigDecimal totalDebits,     // Σ(DEBIT amounts in period)
        BigDecimal totalCredits,    // Σ(CREDIT amounts in period)
        BigDecimal netChange,       // closingBalance − openingBalance
        List<LedgerEntryResponse> entries  // ordered entry_seq ASC
) {
}
