package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.validator.api.EntryValidator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Rejects an entry if applying it would drive the account balance below zero.
 * {@code newBalance} is computed by {@link com.masonx.virtualaccount.domain.ledger.LedgerPostingService}
 * before the validator chain runs, so this check is a pure comparison with no
 * arithmetic of its own. Virtual accounts do not support overdraft by default;
 * a separate validator can be added to permit a configured negative floor if needed.
 */
@Component
public class InsufficientBalanceValidator implements EntryValidator {

    @Override
    public void validate(EntryDraft draft, VaAccount account, BigDecimal newBalance) {
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    "VA_INSUFFICIENT_BALANCE",
                    "Posting would make balance negative for account: " + account.accountId());
        }
    }
}
