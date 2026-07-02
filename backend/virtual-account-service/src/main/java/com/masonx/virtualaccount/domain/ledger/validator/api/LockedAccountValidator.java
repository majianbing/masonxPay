package com.masonx.virtualaccount.domain.ledger.validator.api;

import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingService;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.po.VaAccount;

import java.math.BigDecimal;

/**
 * Validates one posting leg after the account row has been locked and the new
 * balance has been computed. Use this when a check needs both the parent
 * transaction context and the locked account state.
 */
public interface LockedAccountValidator {
    void validate(PostTransaction tx, EntryDraft draft, VaAccount account, BigDecimal newBalance);
}
