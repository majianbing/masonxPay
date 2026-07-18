package com.masonx.virtualaccount.domain.ledger.validator.api;

import com.masonx.virtualaccount.domain.ledger.LedgerPostingService;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;

/**
 * Validates a {@link LedgerPostingCommand} before any accounts are locked or DB state is read.
 * Implementations must be stateless; Spring injects them as an ordered list into
 * {@link LedgerPostingService}. Add a new pre-lock check by creating a {@code @Component}
 * that implements this interface — no changes to the service required.
 */
public interface TransactionValidator {
    void validate(LedgerPostingCommand tx);
}
