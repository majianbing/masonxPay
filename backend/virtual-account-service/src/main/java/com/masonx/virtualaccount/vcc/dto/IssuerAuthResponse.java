package com.masonx.virtualaccount.vcc.dto;

/**
 * Program-manager authorization decision. Network specifics — DE39 response
 * code mapping and DE38 auth code minting — are owned by the issuer/rail side.
 */
public record IssuerAuthResponse(
        String decision,  // APPROVED / DECLINED
        String reason     // machine token when declined (e.g. INSUFFICIENT_FUNDS); null if approved
) {
}
