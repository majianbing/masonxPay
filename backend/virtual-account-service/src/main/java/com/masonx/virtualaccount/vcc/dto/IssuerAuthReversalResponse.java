package com.masonx.virtualaccount.vcc.dto;

import java.math.BigDecimal;

public record IssuerAuthReversalResponse(
        String result,
        String reason,
        BigDecimal releasedAmount,
        BigDecimal remainingHold
) {
}
