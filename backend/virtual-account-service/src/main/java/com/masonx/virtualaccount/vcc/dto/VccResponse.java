package com.masonx.virtualaccount.vcc.dto;

import java.math.BigDecimal;

public record VccResponse(
        String     cardId,
        String     cardTokenId,
        String     maskedPan,
        String     bin,
        String     status,
        BigDecimal balance,
        BigDecimal frozenBalance,
        BigDecimal availableBalance,
        BigDecimal spendingLimit,
        String     currency,
        String     expiry
) {
}
