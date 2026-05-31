package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReusablePaymentMethodDispatcher {

    private final Map<PaymentProvider, ReusablePaymentMethodProviderService> services;

    public ReusablePaymentMethodDispatcher(List<ReusablePaymentMethodProviderService> services) {
        this.services = services.stream()
                .collect(Collectors.toMap(ReusablePaymentMethodProviderService::brand, Function.identity()));
    }

    public ReusablePaymentMethodSetupResult setup(PaymentProvider provider,
                                                  ReusablePaymentMethodSetupRequest request,
                                                  ProviderCredentials creds) {
        return Optional.ofNullable(services.get(provider))
                .orElseThrow(() -> new IllegalStateException(
                        "Provider does not support reusable payment method setup: " + provider))
                .setupReusablePaymentMethod(request, creds);
    }
}
