package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateCardControlsRequest(
        @NotBlank String merchantId,
        String controlsJson
) {
}
