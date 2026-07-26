package com.masonx.virtualaccount.vcc.dto;

public record CardControlResponse(
        String cardId,
        String merchantId,
        String mode,
        String controlsJson
) {
}
