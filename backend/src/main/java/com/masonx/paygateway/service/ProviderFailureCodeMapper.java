package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps provider-specific failure codes to canonical outcome categories used by
 * route policy outcome actions. Each provider has an explicit code→category map;
 * cross-provider system codes (gateway_error, etc.) are handled separately.
 * Returns null for unmapped codes — the orchestrator applies a safe retryable fallback.
 */
@Component
public class ProviderFailureCodeMapper {

    // Canonical category constants — must match keys used in route policy outcome_actions_json.
    public static final String APPROVED               = "APPROVED";
    public static final String REQUIRES_ACTION        = "REQUIRES_ACTION";
    public static final String HARD_DECLINE           = "HARD_DECLINE";
    public static final String RISK_DECLINE           = "RISK_DECLINE";
    public static final String INVALID_PAYMENT_METHOD = "INVALID_PAYMENT_METHOD";
    public static final String INSUFFICIENT_FUNDS     = "INSUFFICIENT_FUNDS";
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String PROVIDER_TIMEOUT       = "PROVIDER_TIMEOUT";
    public static final String PROVIDER_UNAVAILABLE   = "PROVIDER_UNAVAILABLE";
    public static final String PROVIDER_ERROR         = "PROVIDER_ERROR";
    public static final String UNKNOWN_FAILURE        = "UNKNOWN_FAILURE";

    private static final Map<PaymentProvider, Map<String, String>> PROVIDER_MAPS;

    // Codes emitted by infrastructure layers or used as generic cross-provider signals.
    private static final Map<String, String> COMMON_CODES = Map.of(
            "gateway_error",            PROVIDER_UNAVAILABLE,
            "connector_not_configured", PROVIDER_UNAVAILABLE,
            "provider_exception",       PROVIDER_UNAVAILABLE,
            "unexpected_response",      PROVIDER_UNAVAILABLE,
            "timeout",                  PROVIDER_TIMEOUT
    );

    static {
        Map<PaymentProvider, Map<String, String>> maps = new EnumMap<>(PaymentProvider.class);
        maps.put(PaymentProvider.STRIPE,    stripeMap());
        maps.put(PaymentProvider.SQUARE,    squareMap());
        maps.put(PaymentProvider.BRAINTREE, braintreeMap());
        maps.put(PaymentProvider.MOLLIE,    mollieMap());
        maps.put(PaymentProvider.SIMULATOR, simulatorMap());
        PROVIDER_MAPS = Collections.unmodifiableMap(maps);
    }

    /**
     * Returns the canonical outcome category for a provider+code pair, or null if unmapped.
     * Caller should fall back to retryable-based defaults when null is returned.
     */
    public String category(PaymentProvider provider, String failureCode) {
        if (failureCode == null || failureCode.isBlank()) return null;
        String normalized = failureCode.trim().toLowerCase(Locale.ROOT);
        if (provider != null) {
            Map<String, String> map = PROVIDER_MAPS.get(provider);
            if (map != null) {
                String category = map.get(normalized);
                if (category != null) return category;
            }
        }
        return COMMON_CODES.get(normalized);
    }

    private static Map<String, String> stripeMap() {
        return Map.ofEntries(
                Map.entry("card_declined",           HARD_DECLINE),
                Map.entry("do_not_honor",            HARD_DECLINE),
                Map.entry("card_not_supported",      HARD_DECLINE),
                Map.entry("currency_not_supported",  HARD_DECLINE),
                Map.entry("duplicate_transaction",   HARD_DECLINE),
                Map.entry("transaction_not_allowed", HARD_DECLINE),
                Map.entry("no_action_taken",         HARD_DECLINE),
                Map.entry("fraudulent",              RISK_DECLINE),
                Map.entry("lost_card",               RISK_DECLINE),
                Map.entry("stolen_card",             RISK_DECLINE),
                Map.entry("pickup_card",             RISK_DECLINE),
                Map.entry("expired_card",            INVALID_PAYMENT_METHOD),
                Map.entry("incorrect_number",        INVALID_PAYMENT_METHOD),
                Map.entry("invalid_number",          INVALID_PAYMENT_METHOD),
                Map.entry("incorrect_cvc",           INVALID_PAYMENT_METHOD),
                Map.entry("invalid_cvc",             INVALID_PAYMENT_METHOD),
                Map.entry("invalid_expiry_month",    INVALID_PAYMENT_METHOD),
                Map.entry("invalid_expiry_year",     INVALID_PAYMENT_METHOD),
                Map.entry("insufficient_funds",      INSUFFICIENT_FUNDS),
                Map.entry("authentication_required", AUTHENTICATION_REQUIRED),
                Map.entry("service_unavailable",     PROVIDER_UNAVAILABLE),
                Map.entry("api_connection_error",    PROVIDER_UNAVAILABLE),
                Map.entry("rate_limit",              PROVIDER_UNAVAILABLE)
        );
    }

    private static Map<String, String> squareMap() {
        return Map.ofEntries(
                Map.entry("card_declined",                HARD_DECLINE),
                Map.entry("generic_decline",              HARD_DECLINE),
                Map.entry("do_not_honor",                 HARD_DECLINE),
                Map.entry("card_not_supported",           HARD_DECLINE),
                Map.entry("invalid_account",              HARD_DECLINE),
                Map.entry("card_expired",                 INVALID_PAYMENT_METHOD),
                Map.entry("invalid_card",                 INVALID_PAYMENT_METHOD),
                Map.entry("cvv_failure",                  INVALID_PAYMENT_METHOD),
                Map.entry("verify_cvv_failure",           INVALID_PAYMENT_METHOD),
                Map.entry("address_verification_failure", INVALID_PAYMENT_METHOD),
                Map.entry("verify_avs_failure",           INVALID_PAYMENT_METHOD),
                Map.entry("invalid_pin",                  INVALID_PAYMENT_METHOD),
                Map.entry("insufficient_funds",           INSUFFICIENT_FUNDS),
                Map.entry("authorization_error",          AUTHENTICATION_REQUIRED),
                Map.entry("temporary_error",              PROVIDER_UNAVAILABLE),
                Map.entry("service_unavailable",          PROVIDER_UNAVAILABLE),
                Map.entry("gateway_timeout",              PROVIDER_TIMEOUT),
                Map.entry("fraud_risk",                   RISK_DECLINE)
        );
    }

    private static Map<String, String> braintreeMap() {
        return Map.of(
                "processor_declined",  HARD_DECLINE,
                "gateway_rejected",    PROVIDER_UNAVAILABLE,
                "settlement_declined", HARD_DECLINE,
                "payment_failed",      HARD_DECLINE,
                "validation_error",    INVALID_PAYMENT_METHOD
        );
    }

    private static Map<String, String> mollieMap() {
        return Map.of(
                "authorization_failed", HARD_DECLINE,
                "card_declined",        HARD_DECLINE,
                "invalid_card_number",  INVALID_PAYMENT_METHOD,
                "invalid_cvv",          INVALID_PAYMENT_METHOD,
                "card_expired",         INVALID_PAYMENT_METHOD,
                "insufficient_funds",   INSUFFICIENT_FUNDS,
                "fraud_suspected",      RISK_DECLINE
        );
    }

    private static Map<String, String> simulatorMap() {
        return Map.of(
                "simulator_declined",     HARD_DECLINE,
                "simulator_timeout",      PROVIDER_TIMEOUT,
                "simulator_setup_failed", PROVIDER_UNAVAILABLE
        );
    }
}
