package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;

import java.math.BigDecimal;

/**
 * Ledger account — the core primitive of the VA double-entry system.
 * One record represents every scenario (cash, wallet, credit line, platform books,
 * external mirrors) distinguished by role + type, not separate models.
 *
 * balance is materialized on the row and updated atomically with every ledger
 * entry under SELECT FOR UPDATE. There is no account-level frozen/available
 * split: authorization holds are modeled as real ledger entries against a
 * paired hold account (e.g. PREPAID_CARD_HOLD) where that concept applies.
 */
public record LedgerAccount(
        String ledgerAccountId,
        Mode mode,
        LedgerAccountRole ledgerAccountRole,
        String orgId,          // non-null for TENANT
        String merchantId,     // non-null for TENANT
        String providerId,     // non-null for EXTERNAL
        LedgerAccountType ledgerAccountType,
        String asset,
        AssetClass assetClass,
        int scale,
        NormalBalance normalBalance,
        BigDecimal balance,
        LedgerAccountStatus status
) {
    /** Returns a copy with an updated balance — used by LedgerPostingService to track
     *  in-memory state when multiple entries for the same account exist in one transaction. */
    public LedgerAccount withBalance(BigDecimal newBalance) {
        return new LedgerAccount(ledgerAccountId, mode, ledgerAccountRole, orgId, merchantId, providerId,
                ledgerAccountType, asset, assetClass, scale, normalBalance,
                newBalance, status);
    }
}
