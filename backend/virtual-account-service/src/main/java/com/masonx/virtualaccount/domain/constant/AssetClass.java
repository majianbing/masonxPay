package com.masonx.virtualaccount.domain.constant;

/** Asset classification — drives precision rules and allowed operations. */
public enum AssetClass {
    /** Fiat currency (USD, EUR, …). Precision: 2 decimal places. */
    FIAT,
    /** Crypto asset (BTC, USDC, …). Precision: up to 8 decimal places. */
    CRYPTO
}
