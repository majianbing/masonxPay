package com.masonx.rail.canonical;

/**
 * Bank account reference for ISO 20022 payments.
 * Contains IBAN or account number for the simulator — never stored raw in logs.
 */
public record BankAccountRef(
        String iban,      // e.g. DE89370400440532013000
        String bic,       // e.g. DEUTDEDB
        String name       // account holder name
) {
    /** Last 4 digits of IBAN for safe logging. */
    public String maskedIban() {
        if (iban == null || iban.length() < 4) return "****";
        return "****" + iban.substring(iban.length() - 4);
    }
}
