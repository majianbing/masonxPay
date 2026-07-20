package com.masonx.paygateway.web.dto;

import java.math.BigDecimal;

public record VirtualAccountLedgerAccountResponse(
        String ledgerAccountId,
        String mode,
        String ledgerAccountType,
        String accountClass,
        String normalBalance,
        String merchantId,
        String asset,
        BigDecimal balance,
        String status
) {
}
