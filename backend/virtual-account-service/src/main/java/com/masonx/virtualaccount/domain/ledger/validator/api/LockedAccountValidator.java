package com.masonx.virtualaccount.domain.ledger.validator.api;

import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingService;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;

import java.math.BigDecimal;

/**
 * Validates one posting leg after the account row has been locked and the new
 * balance has been computed. Use this when a check needs both the parent
 * transaction context and the locked account state.
 */
public interface LockedAccountValidator {
    void validate(LedgerPostingCommand tx, AccountingEntryDraft draft, LedgerAccount account, BigDecimal newBalance);
}
