package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.TransactionType;

import java.time.LocalDate;
import java.util.List;

/**
 * A balanced set of entry drafts to be posted atomically.
 * LedgerPostingService validates net-zero and asset consistency before inserting.
 * All fields except description and paymentReferenceId are required.
 */
public record LedgerPostingCommand(
        String transactionId,
        List<AccountingEntryDraft> entries,
        TransactionType entryType,
        String description,         // nullable
        String paymentReferenceId,  // nullable; gateway payment_intent_id
        LocalDate effectiveDate,    // accounting date; may differ from system clock
        Mode mode,
        String orgId,               // nullable for PLATFORM/EXTERNAL transactions
        String merchantId           // nullable for PLATFORM/EXTERNAL transactions
) {
}
