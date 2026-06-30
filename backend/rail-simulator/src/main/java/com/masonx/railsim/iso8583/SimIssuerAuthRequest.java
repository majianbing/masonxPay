package com.masonx.railsim.iso8583;

import java.math.BigDecimal;

/** Request sent from rail-simulator → virtual-account-service for BIN 999999 card auths. */
public record SimIssuerAuthRequest(
        String maskedPan,
        BigDecimal amount,
        String currency,
        String stan,
        String rrn
) {
}
