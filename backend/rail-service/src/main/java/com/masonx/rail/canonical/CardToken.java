package com.masonx.rail.canonical;

/**
 * Simulator card reference. Contains a test PAN for building ISO 8583 DE2.
 *
 * <p>IMPORTANT: {@code testPan} is a simulator test value only — never real
 * cardholder data. It must be masked (see {@link #masked()}) before any log
 * write or DB persistence. No production path should populate this field with
 * a real PAN.
 */
public record CardToken(
        String testPan,    // simulator test PAN, e.g. "4111111111110000"
        String expiry,     // MMYY
        String network     // VISA_SIM, MC_SIM — drives BIN routing
) {
    /** Returns the masked form used in all log and DB writes: first 6 + **** + last 4. */
    public String masked() {
        if (testPan == null || testPan.length() < 10) return "****";
        return testPan.substring(0, 6) + "****" + testPan.substring(testPan.length() - 4);
    }

    /** BIN prefix (first 6 digits). Used for routing and logging only. */
    public String bin() {
        return testPan != null && testPan.length() >= 6 ? testPan.substring(0, 6) : "";
    }
}
