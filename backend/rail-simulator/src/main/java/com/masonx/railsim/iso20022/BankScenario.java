package com.masonx.railsim.iso20022;

/**
 * Scenario driven by the last 4 digits of the creditor account number.
 */
public enum BankScenario {
    SETTLE,          // 0000 — ACCP → pacs.002 ACSC → camt.054
    REJECT,          // 0001 — pain.002 RJCT (invalid account)
    RETURN,          // 0002 — ACCP → pacs.002 ACSP → pacs.004 (returned funds)
    PENDING,         // 0003 — ACCP → no further messages (long-pending)
    DUPLICATE_STATUS,// 0004 — ACCP → pacs.002 sent twice
    DELAYED_SETTLE,  // 0005 — ACCP → pacs.002 ACSC on second poll
    AMOUNT_MISMATCH; // 0006 — ACCP → pacs.002 ACSC → camt.054 with wrong amount

    static BankScenario fromAccountSuffix(String accountId) {
        if (accountId == null || accountId.length() < 4) return SETTLE;
        String suffix = accountId.substring(accountId.length() - 4);
        return switch (suffix) {
            case "0001" -> REJECT;
            case "0002" -> RETURN;
            case "0003" -> PENDING;
            case "0004" -> DUPLICATE_STATUS;
            case "0005" -> DELAYED_SETTLE;
            case "0006" -> AMOUNT_MISMATCH;
            default     -> SETTLE;
        };
    }
}
