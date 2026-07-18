package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.validator.api.TransactionValidator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Enforces the double-entry invariant: total DEBITs must equal total CREDITs.
 * A net-non-zero transaction would create or destroy value out of thin air, which
 * corrupts the ledger. This check runs before any accounts are locked so malformed
 * transactions are rejected cheaply without touching the database.
 */
@Component
public class NetZeroValidator implements TransactionValidator {

    @Override
    public void validate(LedgerPostingCommand tx) {
        BigDecimal totalDebits  = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (AccountingEntryDraft d : tx.entries()) {
            if (d.direction() == Direction.DEBIT) totalDebits  = totalDebits.add(d.amount());
            else                                  totalCredits = totalCredits.add(d.amount());
        }
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new BusinessException(
                    "VA_NOT_BALANCED",
                    "Transaction is not balanced: debits (" + totalDebits
                    + ") != credits (" + totalCredits + ")");
        }
    }
}
