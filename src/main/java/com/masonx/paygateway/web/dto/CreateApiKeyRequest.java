package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateApiKeyRequest(
        @NotBlank String name,
        String mode    // TEST | LIVE — defaults to TEST in service if omitted
) {}
