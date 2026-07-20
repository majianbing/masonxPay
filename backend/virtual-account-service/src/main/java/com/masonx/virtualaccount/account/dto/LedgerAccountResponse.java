package com.masonx.virtualaccount.account.dto;

import java.math.BigDecimal;

public record LedgerAccountResponse(
        String     ledgerAccountId,
        String     mode,
        String     ledgerAccountType,
        String     accountClass,
        String     merchantId,
        String     asset,
        BigDecimal balance,
        String     status
) {
}
