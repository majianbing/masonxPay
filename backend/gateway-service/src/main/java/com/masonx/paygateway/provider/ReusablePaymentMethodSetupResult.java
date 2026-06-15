package com.masonx.paygateway.provider;

public record ReusablePaymentMethodSetupResult(
        boolean success,
        String providerCustomerReference,
        String reusablePaymentMethodReference,
        String providerResponseJson,
        String failureCode,
        String failureMessage,
        boolean retryable,
        boolean requiresAction,
        String actionType,
        String actionUrl,
        String clientSecret
) {
    public static ReusablePaymentMethodSetupResult succeeded(String providerCustomerReference,
                                                            String reusablePaymentMethodReference,
                                                            String providerResponseJson) {
        return new ReusablePaymentMethodSetupResult(true, providerCustomerReference, reusablePaymentMethodReference,
                providerResponseJson, null, null, false, false, null, null, null);
    }

    public static ReusablePaymentMethodSetupResult failed(String failureCode,
                                                         String failureMessage,
                                                         boolean retryable) {
        return new ReusablePaymentMethodSetupResult(false, null, null, null,
                failureCode, failureMessage, retryable, false, null, null, null);
    }

    public static ReusablePaymentMethodSetupResult actionRequired(String providerCustomerReference,
                                                                  String providerResponseJson,
                                                                  String actionType,
                                                                  String actionUrl,
                                                                  String clientSecret) {
        return new ReusablePaymentMethodSetupResult(false, providerCustomerReference, null, providerResponseJson,
                null, null, false, true, actionType, actionUrl, clientSecret);
    }
}
