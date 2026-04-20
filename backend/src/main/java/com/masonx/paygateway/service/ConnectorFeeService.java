package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import org.springframework.stereotype.Service;

/**
 * Phase 3.5: Computes the effective cost of processing a payment through a given connector.
 *
 * Cost model (flat rate per account — Option A):
 *   effectiveCost = fixedFeeCents + (amount × rateBps / 10_000)
 *
 * Both values are stored on ProviderAccount and default to 0, so connectors without
 * fee configuration have effectiveCost = 0 and are never excluded by the cost filter.
 *
 * Future: replace getEffectiveCost() lookup with a fee-schedule table for interchange-level
 * pricing (card type, country) — the signature and callers stay the same.
 */
@Service
public class ConnectorFeeService {

    /**
     * Returns the effective cost in cents for processing {@code amount} (in cents)
     * through the given connector account.
     *
     * Example: fixedFeeCents=30, rateBps=290, amount=10_000 ($100)
     *   → 30 + (10_000 × 290 / 10_000) = 30 + 290 = 320 cents ($3.20)
     */
    public long effectiveCost(ProviderAccount account, long amount) {
        return account.getFixedFeeCents() + (amount * account.getRateBps() / 10_000L);
    }

    /**
     * Returns true if the connector's effective cost for this amount exceeds the ceiling.
     *
     * @param maxCostBps ceiling in basis points of the transaction amount (e.g. 300 = 3.00%)
     */
    public boolean exceedsCeiling(ProviderAccount account, long amount, int maxCostBps) {
        long ceiling = amount * maxCostBps / 10_000L;
        return effectiveCost(account, amount) > ceiling;
    }

    /**
     * Formats the fee configuration as a human-readable string for logging.
     * e.g. "$0.30 + 2.90%"
     */
    public static String describe(ProviderAccount account) {
        double fixed = account.getFixedFeeCents() / 100.0;
        double rate  = account.getRateBps() / 100.0;
        return String.format("$%.2f + %.2f%%", fixed, rate);
    }
}
