package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
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

/**
 * Routes charge/refund calls to the correct provider implementation.
 * All registered PaymentProviderService beans are auto-discovered via the List injection.
 * Every outbound call is bracketed with INFO/WARN/ERROR log lines via SLF4J so that
 * request parameters and response outcomes are visible in structured application logs
 * without touching provider credentials or raw payment method tokens.
 */
@Service
public class PaymentProviderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderDispatcher.class);

    private final Map<PaymentProvider, PaymentProviderService> services;

    public PaymentProviderDispatcher(List<PaymentProviderService> services) {
        this.services = services.stream()
                .collect(Collectors.toMap(PaymentProviderService::brand, Function.identity()));
    }

    public ChargeResult charge(PaymentProvider provider, ChargeRequest req, ProviderCredentials creds) {
        long start = System.currentTimeMillis();
        log.info("provider.call.start op=CHARGE provider={} paymentIntentId={} amount={} currency={} idempotencyKey={} captureMethod={}",
                provider, req.paymentIntentId(), req.amount(), req.currency(), req.idempotencyKey(), req.captureMethod());
        try {
            ChargeResult result = get(provider).charge(req, creds);
            long durationMs = System.currentTimeMillis() - start;
            if (result.success() || result.requiresAction()) {
                log.info("provider.call.end op=CHARGE provider={} paymentIntentId={} success={} providerPaymentId={} requiresAction={} durationMs={}",
                        provider, req.paymentIntentId(), result.success(), result.providerPaymentId(), result.requiresAction(), durationMs);
            } else {
                log.warn("provider.call.end op=CHARGE provider={} paymentIntentId={} success=false failureCode={} retryable={} durationMs={}",
                        provider, req.paymentIntentId(), result.failureCode(), result.retryable(), durationMs);
            }
            return result;
        } catch (RuntimeException e) {
            log.error("provider.call.error op=CHARGE provider={} paymentIntentId={} durationMs={} error={}",
                    provider, req.paymentIntentId(), System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    public RefundResult refund(PaymentProvider provider, RefundRequest req, ProviderCredentials creds) {
        long start = System.currentTimeMillis();
        log.info("provider.call.start op=REFUND provider={} refundId={} providerPaymentId={} amount={} reason={}",
                provider, req.refundId(), req.providerPaymentId(), req.amount(), req.reason());
        try {
            RefundResult result = get(provider).refund(req, creds);
            long durationMs = System.currentTimeMillis() - start;
            if (result.success()) {
                log.info("provider.call.end op=REFUND provider={} refundId={} providerRefundId={} durationMs={}",
                        provider, req.refundId(), result.providerRefundId(), durationMs);
            } else {
                log.warn("provider.call.end op=REFUND provider={} refundId={} success=false failureReason={} durationMs={}",
                        provider, req.refundId(), result.failureReason(), durationMs);
            }
            return result;
        } catch (RuntimeException e) {
            log.error("provider.call.error op=REFUND provider={} refundId={} durationMs={} error={}",
                    provider, req.refundId(), System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    public Optional<PaymentIntentStatus> syncStatus(PaymentProvider provider, String providerPaymentId, ProviderCredentials creds) {
        long start = System.currentTimeMillis();
        log.info("provider.call.start op=SYNC_STATUS provider={} providerPaymentId={}",
                provider, providerPaymentId);
        try {
            Optional<PaymentIntentStatus> result = get(provider).syncStatus(providerPaymentId, creds);
            log.info("provider.call.end op=SYNC_STATUS provider={} providerPaymentId={} resolvedStatus={} durationMs={}",
                    provider, providerPaymentId, result.map(Enum::name).orElse("in-flight"), System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            log.error("provider.call.error op=SYNC_STATUS provider={} providerPaymentId={} durationMs={} error={}",
                    provider, providerPaymentId, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    public boolean cancelAtProvider(PaymentProvider provider, String providerPaymentId, ProviderCredentials creds) {
        long start = System.currentTimeMillis();
        log.info("provider.call.start op=CANCEL provider={} providerPaymentId={}",
                provider, providerPaymentId);
        try {
            boolean accepted = get(provider).cancelAtProvider(providerPaymentId, creds);
            log.info("provider.call.end op=CANCEL provider={} providerPaymentId={} accepted={} durationMs={}",
                    provider, providerPaymentId, accepted, System.currentTimeMillis() - start);
            return accepted;
        } catch (RuntimeException e) {
            log.error("provider.call.error op=CANCEL provider={} providerPaymentId={} durationMs={} error={}",
                    provider, providerPaymentId, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    public boolean captureAtProvider(PaymentProvider provider, String providerPaymentId, ProviderCredentials creds) {
        long start = System.currentTimeMillis();
        log.info("provider.call.start op=CAPTURE provider={} providerPaymentId={}",
                provider, providerPaymentId);
        try {
            boolean accepted = get(provider).captureAtProvider(providerPaymentId, creds);
            log.info("provider.call.end op=CAPTURE provider={} providerPaymentId={} accepted={} durationMs={}",
                    provider, providerPaymentId, accepted, System.currentTimeMillis() - start);
            return accepted;
        } catch (RuntimeException e) {
            log.error("provider.call.error op=CAPTURE provider={} providerPaymentId={} durationMs={} error={}",
                    provider, providerPaymentId, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    private PaymentProviderService get(PaymentProvider provider) {
        return Optional.ofNullable(services.get(provider))
                .orElseThrow(() -> new IllegalStateException(
                        "No payment service registered for provider: " + provider));
    }
}
