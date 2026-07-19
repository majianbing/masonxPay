package com.masonx.virtualaccount.domain.po;

import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A funded virtual card issued against a PREPAID_CARD LedgerAccount.
 *
 * <p>cardTokenId is the simulator card identity used by issuer decisions.
 * maskedPan stores only the display/audit form (e.g. {@code 4111****1234}).
 *
 * <p>vccAccountId points to the PREPAID_CARD account that holds the loaded balance.
 * holdAccountId points to the paired PREPAID_CARD_HOLD account for authorized
 * but unsettled funds.
 * ownerAccountId points to the WALLET account that funded the card.
 * Closing the card sweeps remaining balance from vccAccount back to ownerAccount.
 */
public record VirtualCard(
        String cardId,
        String cardTokenId,
        String maskedPan,
        String bin,
        String vccAccountId,
        String holdAccountId,
        String ownerAccountId,
        VirtualCardStatus status,
        BigDecimal spendingLimit,
        String currency,
        LocalDate expiry,
        Instant createdAt,
        Instant updatedAt
) {
}
