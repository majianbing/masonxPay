package com.masonx.paygateway.service;

import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Classifies charge failure codes as retryable (technical / transient) or
 * non-retryable (hard card decline).  Retryable failures trigger the failover
 * loop in PaymentIntentService — the next eligible connector will be tried.
 * Hard declines skip failover immediately; retrying won't change the outcome.
 */
@Service
public class FailoverPolicy {

    /**
     * Normalised failure codes that represent hard declines.
     * Retrying a different connector with the same card will not help.
     */
    private static final Set<String> HARD_DECLINE_CODES = Set.of(
            // Stripe
            "card_declined",
            "insufficient_funds",
            "expired_card",
            "incorrect_cvc",
            "incorrect_number",
            "do_not_honor",
            "fraudulent",
            "lost_card",
            "stolen_card",
            "pickup_card",
            "restricted_card",
            "card_not_supported",
            "currency_not_supported",
            // Square (lower-cased)
            "cvv_failure",
            "invalid_expiration",
            "pan_failure",
            "card_velocity_exceeded",
            // Internal sentinel codes — not worth retrying
            "connector_not_configured",
            "preview_unsupported"
    );

    /**
     * Returns {@code true} when it is worth trying a different connector.
     * Technical errors (network timeouts, API errors, rate-limits, etc.) are
     * retryable; hard card declines are not.
     */
    public boolean isRetryable(String failureCode) {
        if (failureCode == null) return false;
        return !HARD_DECLINE_CODES.contains(failureCode.toLowerCase());
    }
}
