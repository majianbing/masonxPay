package com.masonx.virtualaccount.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TrialBalanceResponse(
        String mode,
        String asset,
        Instant asOf,
        BigDecimal totalDebitSideBalance,   // Σ(balance on DEBIT-normal accounts)
        BigDecimal totalCreditSideBalance,  // Σ(balance on CREDIT-normal accounts)
        boolean balanced,                   // totalDebitSide.compareTo(totalCreditSide) == 0
        List<TrialBalanceRow> rows
) {
    public record TrialBalanceRow(
            String ledgerAccountId,
            String ledgerAccountType,
            String ledgerAccountRole,
            String merchantId,   // null for PLATFORM/EXTERNAL
            String normalBalance,
            BigDecimal balance
    ) {}
}
