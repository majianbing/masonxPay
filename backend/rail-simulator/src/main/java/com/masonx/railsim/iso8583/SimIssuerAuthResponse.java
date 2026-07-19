package com.masonx.railsim.iso8583;

/**
 * Decision from the virtual-account-service program-manager endpoint.
 * Mapping to ISO 8583 (DE39 response code, DE38 auth code) happens on this
 * side — the network/issuer layer owns the ISO vocabulary.
 */
public record SimIssuerAuthResponse(
        String decision,  // "APPROVED" or "DECLINED"
        String reason     // machine token when declined (e.g. INSUFFICIENT_FUNDS); null if approved
) {
}
