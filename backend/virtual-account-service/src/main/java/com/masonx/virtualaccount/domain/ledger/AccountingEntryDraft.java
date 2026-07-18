package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.constant.Direction;

import java.math.BigDecimal;

/**
 * One leg of a double-entry transaction before seq/signature are computed.
 * LedgerPostingService resolves drafts into immutable LedgerEntry rows.
 */
public record AccountingEntryDraft(
        String ledgerAccountId,
        Direction direction,
        BigDecimal amount,
        String asset,
        String sourceEventId
) {
}
