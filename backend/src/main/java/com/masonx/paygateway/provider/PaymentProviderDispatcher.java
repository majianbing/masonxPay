package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes charge/refund calls to the correct provider implementation.
 * All registered PaymentProviderService beans are auto-discovered via the List injection.
 */
@Service
public class PaymentProviderDispatcher {

    private final Map<PaymentProvider, PaymentProviderService> services;

    public PaymentProviderDispatcher(List<PaymentProviderService> services) {
        this.services = services.stream()
                .collect(Collectors.toMap(PaymentProviderService::brand, Function.identity()));
    }

    public ChargeResult charge(PaymentProvider provider, ChargeRequest req, ProviderCredentials creds) {
        return get(provider).charge(req, creds);
    }

    public RefundResult refund(PaymentProvider provider, RefundRequest req, ProviderCredentials creds) {
        return get(provider).refund(req, creds);
    }

    public Optional<PaymentIntentStatus> syncStatus(PaymentProvider provider, String providerPaymentId, ProviderCredentials creds) {
        return get(provider).syncStatus(providerPaymentId, creds);
    }

    public boolean cancelAtProvider(PaymentProvider provider, String providerPaymentId, ProviderCredentials creds) {
        return get(provider).cancelAtProvider(providerPaymentId, creds);
    }

    private PaymentProviderService get(PaymentProvider provider) {
        return Optional.ofNullable(services.get(provider))
                .orElseThrow(() -> new IllegalStateException(
                        "No payment service registered for provider: " + provider));
    }
}
