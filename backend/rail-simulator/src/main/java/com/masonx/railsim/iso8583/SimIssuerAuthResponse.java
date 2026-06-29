package com.masonx.railsim.iso8583;

/** Response from virtual-account-service issuer endpoint. */
public record SimIssuerAuthResponse(
        String decision,      // "APPROVED" or "DECLINED"
        String responseCode,  // DE39: "00"=approved, "51"=insufficient funds, etc.
        String authCode,      // DE38: 6-char auth code if approved; null otherwise
        String reason         // human-readable decline reason; null if approved
) {
}
