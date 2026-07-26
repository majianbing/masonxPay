package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CardLifecycleRequest(
        @NotBlank String merchantId,
        @Size(max = 200) String reason
) {
}
