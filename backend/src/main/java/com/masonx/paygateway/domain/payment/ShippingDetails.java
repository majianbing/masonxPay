package com.masonx.paygateway.domain.payment;

/**
 * Delivery destination for physical goods.
 * Passed to providers for Radar fraud scoring and stored as chargeback evidence.
 */
public record ShippingDetails(
        String firstName,
        String lastName,
        String phone,
        Address address
) {}
