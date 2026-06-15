package com.masonx.paygateway.domain.payment;

/**
 * Payer / cardholder details passed to the provider at charge time.
 * Used for AVS checks (address), Stripe Radar scoring, 3DS2 authentication,
 * and receipt delivery (email).
 */
public record BillingDetails(
        String firstName,
        String lastName,
        String email,
        String phone,
        Address address
) {}
