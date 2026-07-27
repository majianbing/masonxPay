package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CardLifecycleRequest(
        @NotBlank String merchantId,
        String mode,
        @Size(max = 200) String reason
) {
    public CardLifecycleRequest(String merchantId, String reason) {
        this(merchantId, null, reason);
    }
}
