package com.masonx.virtualaccount.domain.ledger;

import java.util.List;

/**
 * A balanced set of entry drafts to be posted atomically.
 * LedgerPostingService validates net-zero and asset consistency before inserting.
 */
public record PostTransaction(
        String transactionId,
        List<EntryDraft> entries
) {
}
