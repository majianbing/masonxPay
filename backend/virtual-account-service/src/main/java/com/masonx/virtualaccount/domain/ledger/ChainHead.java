package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.constant.Direction;

import java.math.BigDecimal;

/**
 * The last entry for an account — everything needed to both verify that entry's
 * signature and derive the anchor for the next entry.
 *
 * Returned by {@link LedgerEntryRepository#findLastChainHead(String)} inside
 * the posting transaction (after SELECT FOR UPDATE on the account row), so the
 * data is consistent with the locked account state.
 */
public record ChainHead(
        long entrySeq,
        BigDecimal amount,
        String asset,
        Direction direction,
        BigDecimal balanceAfter,
        String transactionId,
        String prevSignature,
        String signature,         // balance_signature of this entry; becomes prevSignature for next
        String signatureKeyId
) {
    public ChainAnchor toAnchor() {
        return new ChainAnchor(entrySeq, signature);
    }
}
