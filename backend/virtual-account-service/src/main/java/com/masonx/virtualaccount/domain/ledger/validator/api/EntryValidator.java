package com.masonx.virtualaccount.domain.ledger.validator.api;

import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingService;

import java.math.BigDecimal;

/**
 * Validates a single {@link EntryDraft} after accounts are locked and the new balance
 * has been computed. Spring injects implementations as an ordered list into
 * {@link LedgerPostingService}. Add a new per-entry check by creating a {@code @Component}
 * that implements this interface — no changes to the service required.
 *
 * @param draft        the entry being posted
 * @param account      the locked account at the time of posting
 * @param newBalance   the balance that would result from applying this entry
 */
public interface EntryValidator {
    void validate(EntryDraft draft, VaAccount account, BigDecimal newBalance);
}
