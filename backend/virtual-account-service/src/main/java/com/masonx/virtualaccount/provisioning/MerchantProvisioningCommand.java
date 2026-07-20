package com.masonx.virtualaccount.provisioning;

import com.masonx.common.tenant.Mode;

import java.util.List;

public record MerchantProvisioningCommand(
        String eventId,
        String organizationId,
        String merchantId,
        String merchantName,
        List<Mode> modes,
        String asset
) {
}
