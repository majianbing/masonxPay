package com.masonx.paygateway.domain.payment;

/**
 * A physical address — shared by BillingDetails and ShippingDetails.
 * country should be ISO 3166-1 alpha-2 (e.g. "US", "GB", "DE").
 */
public record Address(
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country
) {}
