package com.masonx.railsim.iso8583;

import java.math.BigDecimal;

/**
 * Request sent from rail-simulator → virtual-account-service for BIN 999999 card auths.
 *
 * <p>{@code authorizationId} is minted here (the issuer/processor side), is unique
 * per distinct authorization, and is reused verbatim when the call is retried.
 * {@code cardTokenId} is deterministically derived from the simulator test PAN,
 * so the simulator does not need card storage and VA does not identify cards by
 * masked display PAN.
 */
public record SimIssuerAuthRequest(
        String authorizationId,
        String cardTokenId,
        BigDecimal amount,
        String currency,
        String stan,
        String rrn
) {
}
