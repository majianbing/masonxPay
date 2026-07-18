package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.validator.api.LockedAccountValidator;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Ensures a posting leg targets an account in the same asset, mode, and tenant
 * scope declared by the journal header.
 */
@Component
public class AccountScopeValidator implements LockedAccountValidator {

    @Override
    public void validate(LedgerPostingCommand tx, AccountingEntryDraft draft, LedgerAccount account, BigDecimal newBalance) {
        if (!account.asset().equals(draft.asset())) {
            throw new BusinessException("VA_ACCOUNT_ASSET_MISMATCH",
                    "Entry asset " + draft.asset() + " does not match account asset "
                    + account.asset() + " for account: " + account.ledgerAccountId());
        }
        if (account.mode() != tx.mode()) {
            throw new BusinessException("VA_ACCOUNT_MODE_MISMATCH",
                    "Transaction mode " + tx.mode() + " does not match account mode "
                    + account.mode() + " for account: " + account.ledgerAccountId());
        }
        if (account.ledgerAccountRole() == LedgerAccountRole.TENANT
                && (tx.merchantId() == null || !tx.merchantId().equals(account.merchantId()))) {
            throw new BusinessException("VA_ACCOUNT_TENANT_MISMATCH",
                    "Transaction merchant does not match tenant account: " + account.ledgerAccountId());
        }
    }
}
