package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateCardControlsRequest(
        @NotBlank String merchantId,
        String mode,
        String controlsJson
) {
    public UpdateCardControlsRequest(String merchantId, String controlsJson) {
        this(merchantId, null, controlsJson);
    }
}
