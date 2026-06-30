package com.masonx.paygateway.provider;

/**
 * Result of a charge attempt against a payment provider.
 *
 * For standard outcomes (success / failure):
 *   new ChargeResult(success, providerPaymentId, json, failureCode, failureMessage, retryable,
 *                    false, false, null, null, null)
 *
 * For 3DS / SCA challenges use the static factory:
 *   ChargeResult.actionRequired(providerPaymentId, json, actionType, actionUrl, clientSecret)
 *
 * Action types:
 *   "stripe_sdk"   — Stripe 3DS2: SDK calls stripe.handleNextAction({ clientSecret })
 *   "redirect_url" — Any provider redirect: SDK opens actionUrl in an iframe overlay
 */
public record ChargeResult(
        boolean success,
        String  providerPaymentId,
        String  providerResponseJson,
        String  failureCode,
        String  failureMessage,
        boolean retryable,
        boolean pendingAsyncResolution, // true only for rail UNKNOWN state
        // 3DS / SCA fields — all null/false for non-action outcomes
        boolean requiresAction,
        String  actionType,      // "stripe_sdk" | "redirect_url"
        String  actionUrl,       // redirect URL for "redirect_url" type; null for "stripe_sdk"
        String  clientSecret     // Stripe PI client_secret for "stripe_sdk" type; null otherwise
) {
    /** Convenience factory for a 3DS action-required result. */
    public static ChargeResult actionRequired(String providerPaymentId, String providerResponseJson,
                                              String actionType, String actionUrl, String clientSecret) {
        return new ChargeResult(false, providerPaymentId, providerResponseJson,
                null, null, false, false, true, actionType, actionUrl, clientSecret);
    }
}
