package com.masonx.virtualaccount.account.dto;

import java.math.BigDecimal;

public record AccountResponse(
        String     accountId,
        String     mode,
        String     accountType,
        String     merchantId,
        String     asset,
        BigDecimal balance,
        BigDecimal frozenBalance,
        BigDecimal availableBalance,
        String     status
) {
}
