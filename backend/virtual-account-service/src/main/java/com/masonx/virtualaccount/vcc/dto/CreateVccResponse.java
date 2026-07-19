package com.masonx.virtualaccount.vcc.dto;

/**
 * Response for VCC creation. Contains {@code testPan} — the simulator PAN to use in
 * ISO 8583 authorization requests. This value is returned ONCE at creation and is NOT
 * stored in the database (only the masked form is persisted).
 */
public record CreateVccResponse(
        String cardId,
        String cardTokenId,
        String testPan,    // full test PAN — returned once for simulator ISO 8583 DE2
        String maskedPan,
        String bin,
        String currency,
        String expiry      // ISO date string or null
) {
}
