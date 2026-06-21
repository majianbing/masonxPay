package com.masonx.virtualaccount.domain.ledger;

/**
 * The last entry's seq and signature for a given account — the anchor point
 * for computing the next entry in the HMAC hash chain.
 */
public record ChainAnchor(long entrySeq, String signature) {
    public static final String GENESIS_SIGNATURE = "GENESIS";
}
