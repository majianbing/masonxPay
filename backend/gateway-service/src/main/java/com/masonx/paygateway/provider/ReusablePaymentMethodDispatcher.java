package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReusablePaymentMethodDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReusablePaymentMethodDispatcher.class);

    private final Map<PaymentProvider, ReusablePaymentMethodProviderService> services;

    public ReusablePaymentMethodDispatcher(List<ReusablePaymentMethodProviderService> services) {
        this.services = services.stream()
                .collect(Collectors.toMap(ReusablePaymentMethodProviderService::brand, Function.identity()));
    }

    public ReusablePaymentMethodSetupResult setup(PaymentProvider provider,
                                                  ReusablePaymentMethodSetupRequest request,
                                                  ProviderCredentials creds) {
        long start = System.currentTimeMillis();
        log.info("provider.call.start op=SETUP_METHOD provider={} customerId={} subscriptionId={} idempotencyKey={}",
                provider, request.customerId(), request.subscriptionId(), request.idempotencyKey());
        try {
            ReusablePaymentMethodSetupResult result = Optional.ofNullable(services.get(provider))
                    .orElseThrow(() -> new IllegalStateException(
                            "Provider does not support reusable payment method setup: " + provider))
                    .setupReusablePaymentMethod(request, creds);
            long durationMs = System.currentTimeMillis() - start;
            if (result.success() || result.requiresAction()) {
                log.info("provider.call.end op=SETUP_METHOD provider={} customerId={} success={} hasCustomerRef={} requiresAction={} durationMs={}",
                        provider, request.customerId(), result.success(),
                        result.providerCustomerReference() != null, result.requiresAction(), durationMs);
            } else {
                log.warn("provider.call.end op=SETUP_METHOD provider={} customerId={} success=false failureCode={} retryable={} durationMs={}",
                        provider, request.customerId(), result.failureCode(), result.retryable(), durationMs);
            }
            return result;
        } catch (RuntimeException e) {
            log.error("provider.call.error op=SETUP_METHOD provider={} customerId={} durationMs={} error={}",
                    provider, request.customerId(), System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }
}
