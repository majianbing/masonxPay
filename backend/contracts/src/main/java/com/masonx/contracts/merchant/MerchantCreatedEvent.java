package com.masonx.contracts.merchant;

import com.masonx.contracts.EventEnvelope;

import java.util.List;

/**
 * Published by gateway-service after a merchant is created.
 *
 * <p>Consumers such as virtual-account-service use this as a business fact and
 * provision their own optional resources asynchronously. The event deliberately
 * does not describe VA ledger account types or other consumer-owned details.
 */
public record MerchantCreatedEvent(
        EventEnvelope envelope,
        String organizationId,
        String merchantId,
        String merchantExternalId,
        String merchantName,
        List<String> modes,
        String defaultAsset
) {
    public static final String TYPE = "merchant.created";
    public static final int SCHEMA_VERSION = 1;
}
