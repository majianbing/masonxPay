package com.masonx.virtualaccount.provisioning.dto;

import com.masonx.common.tenant.Mode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ProvisionMerchantRequest(
        @NotBlank String organizationId,
        @NotBlank String merchantId,
        @NotBlank String merchantName,
        List<Mode> modes,
        String asset
) {
}
