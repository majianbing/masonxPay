package com.masonx.virtualaccount.vcc.dto;

public record IssuerAuthResponse(
        String decision,     // "APPROVED" or "DECLINED"
        String responseCode, // DE39: "00"=approved, "51"=insufficient funds, "14"=invalid card, etc.
        String authCode,     // DE38: 6-char auth code if approved; null otherwise
        String reason        // human-readable decline reason; null if approved
) {
}
