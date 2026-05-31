package com.masonx.paygateway.provider.simulator;

import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderService;
import com.masonx.paygateway.provider.ReusablePaymentMethodProviderService;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupRequest;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupResult;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-process fake PSP used by H7 benchmarks.
 * It is wired as a normal provider adapter so confirm/capture/refund benchmarks
 * still exercise routing, credential loading, retry orchestration, DB state
 * transitions, outbox writes, and optional Kafka/Redis side paths.
 */
@Service
@ConditionalOnProperty(prefix = "app.provider-simulator", name = "enabled", havingValue = "true")
public class MasonSimulatorPaymentProviderService implements PaymentProviderService, ReusablePaymentMethodProviderService {

    private final ProviderSimulatorProperties properties;

    public MasonSimulatorPaymentProviderService(ProviderSimulatorProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.SIMULATOR;
    }

    @Override
    public ChargeResult charge(ChargeRequest request, ProviderCredentials creds) {
        simulateLatencyAndTimeout();
        if (shouldFail(creds)) {
            return new ChargeResult(false, null, json("charge", "failed", null),
                    "simulator_declined", "Mason Simulator synthetic decline",
                    false, false, null, null, null);
        }
        String providerPaymentId = "sim_pay_" + request.paymentIntentId();
        return new ChargeResult(true, providerPaymentId, json("charge", "succeeded", providerPaymentId),
                null, null, false, false, null, null, null);
    }

    @Override
    public RefundResult refund(RefundRequest request, ProviderCredentials creds) {
        simulateLatencyAndTimeout();
        if (shouldFail(creds)) {
            return new RefundResult(false, null, "Mason Simulator synthetic refund failure");
        }
        return new RefundResult(true, "sim_ref_" + request.refundId(), null);
    }

    @Override
    public ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request,
            ProviderCredentials creds) {
        simulateLatencyAndTimeout();
        if (shouldFail(creds)) {
            return ReusablePaymentMethodSetupResult.failed(
                    "simulator_setup_failed",
                    "Mason Simulator synthetic reusable method setup failure",
                    false);
        }
        String customerReference = request.existingProviderCustomerReference() != null
                && !request.existingProviderCustomerReference().isBlank()
                ? request.existingProviderCustomerReference()
                : "sim_cus_" + request.customerId();
        String reusableReference = "sim_pm_reusable_" + request.customerId();
        return ReusablePaymentMethodSetupResult.succeeded(
                customerReference,
                reusableReference,
                json("setup_reusable_method", "succeeded", reusableReference));
    }

    @Override
    public Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds) {
        simulateLatencyAndTimeout();
        return Optional.of(PaymentIntentStatus.SUCCEEDED);
    }

    @Override
    public boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
        simulateLatencyAndTimeout();
        return !shouldFail(creds);
    }

    @Override
    public boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
        simulateLatencyAndTimeout();
        return !shouldFail(creds);
    }

    private void simulateLatencyAndTimeout() {
        double random = ThreadLocalRandom.current().nextDouble();
        if (random < properties.getTimeoutRate()) {
            sleep(properties.getTimeoutLatencyMs());
            throw new RuntimeException("Mason Simulator synthetic timeout");
        }
        long jitter = properties.getJitterMs() > 0
                ? ThreadLocalRandom.current().nextLong(properties.getJitterMs() + 1)
                : 0;
        sleep(properties.getBaseLatencyMs() + jitter);
    }

    private boolean shouldFail(ProviderCredentials creds) {
        // Prefer the connector-specific simulator success rate so one merchant
        // can model a healthy PSP while another models a degraded PSP.
        double successRate = creds instanceof SimulatorCredentials simulator
                ? simulator.successRate()
                : 1.0 - properties.getFailureRate();
        return ThreadLocalRandom.current().nextDouble() >= successRate;
    }

    private void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Mason Simulator interrupted", ex);
        }
    }

    private String json(String operation, String status, String id) {
        String requestId = "sim_req_" + UUID.randomUUID();
        return "{\"provider\":\"SIMULATOR\",\"operation\":\"" + operation
                + "\",\"status\":\"" + status
                + "\",\"id\":\"" + (id != null ? id : "")
                + "\",\"requestId\":\"" + requestId + "\"}";
    }
}
