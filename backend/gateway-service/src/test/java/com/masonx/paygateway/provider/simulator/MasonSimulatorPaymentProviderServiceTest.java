package com.masonx.paygateway.provider.simulator;

import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupRequest;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MasonSimulatorPaymentProviderServiceTest {

    @Test
    void charge_returnsSuccessfulSyntheticProviderPayment() {
        MasonSimulatorPaymentProviderService service = service();
        UUID intentId = UUID.randomUUID();

        var result = service.charge(new ChargeRequest(
                intentId,
                1_000,
                "USD",
                "card",
                "sim_pm_card_visa",
                null,
                "idem-1",
                null,
                null,
                CaptureMethod.AUTOMATIC,
                null), new SimulatorCredentials(true, 1.0));

        assertThat(service.brand()).isEqualTo(PaymentProvider.SIMULATOR);
        assertThat(result.success()).isTrue();
        assertThat(result.providerPaymentId()).isEqualTo("sim_pay_" + intentId);
        assertThat(result.providerResponseJson()).contains("\"provider\":\"SIMULATOR\"");
    }

    @Test
    void refund_returnsSuccessfulSyntheticRefund() {
        MasonSimulatorPaymentProviderService service = service();
        UUID refundId = UUID.randomUUID();

        var result = service.refund(new RefundRequest(
                refundId,
                "sim_pay_" + UUID.randomUUID(),
                500,
                "BENCHMARK"), new SimulatorCredentials(true, 1.0));

        assertThat(result.success()).isTrue();
        assertThat(result.providerRefundId()).isEqualTo("sim_ref_" + refundId);
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void providerStateOperationsReturnDeterministicSuccess() {
        MasonSimulatorPaymentProviderService service = service();

        assertThat(service.syncStatus("sim_pay_123", new SimulatorCredentials(true, 1.0)))
                .contains(PaymentIntentStatus.SUCCEEDED);
        assertThat(service.captureAtProvider("sim_pay_123", new SimulatorCredentials(true, 1.0))).isTrue();
        assertThat(service.cancelAtProvider("sim_pay_123", new SimulatorCredentials(true, 1.0))).isTrue();
    }

    @Test
    void charge_usesConnectorSuccessRateToForceFailure() {
        MasonSimulatorPaymentProviderService service = service();

        var result = service.charge(new ChargeRequest(
                UUID.randomUUID(),
                1_000,
                "USD",
                "card",
                "sim_pm_card_visa",
                null,
                "idem-2",
                null,
                null,
                CaptureMethod.AUTOMATIC,
                null), new SimulatorCredentials(true, 0.0));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("simulator_declined");
    }

    @Test
    void setupReusablePaymentMethod_returnsReusableReferences() {
        MasonSimulatorPaymentProviderService service = service();
        UUID customerId = UUID.randomUUID();

        var result = service.setupReusablePaymentMethod(
                new ReusablePaymentMethodSetupRequest(
                        UUID.randomUUID(), customerId, UUID.randomUUID(), UUID.randomUUID(),
                        "card", "sim_pm_tok", null, "idem-setup-1", null, null),
                new SimulatorCredentials(true, 1.0));

        assertThat(result.success()).isTrue();
        assertThat(result.providerCustomerReference()).isEqualTo("sim_cus_" + customerId);
        assertThat(result.reusablePaymentMethodReference()).isEqualTo("sim_pm_reusable_" + customerId);
    }

    @Test
    void setupReusablePaymentMethod_withExistingCustomerReference_reusesIt() {
        MasonSimulatorPaymentProviderService service = service();
        UUID customerId = UUID.randomUUID();

        var result = service.setupReusablePaymentMethod(
                new ReusablePaymentMethodSetupRequest(
                        UUID.randomUUID(), customerId, UUID.randomUUID(), UUID.randomUUID(),
                        "card", "sim_pm_tok", "existing_cus_xyz", "idem-setup-2", null, null),
                new SimulatorCredentials(true, 1.0));

        assertThat(result.success()).isTrue();
        assertThat(result.providerCustomerReference()).isEqualTo("existing_cus_xyz");
    }

    @Test
    void setupReusablePaymentMethod_simulatedFailure_returnsFailedResult() {
        MasonSimulatorPaymentProviderService service = service();

        var result = service.setupReusablePaymentMethod(
                new ReusablePaymentMethodSetupRequest(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "card", "sim_pm_tok", null, "idem-setup-3", null, null),
                new SimulatorCredentials(true, 0.0));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("simulator_setup_failed");
    }

    private MasonSimulatorPaymentProviderService service() {
        ProviderSimulatorProperties properties = new ProviderSimulatorProperties();
        properties.setBaseLatencyMs(0);
        properties.setJitterMs(0);
        properties.setFailureRate(0);
        properties.setTimeoutRate(0);
        return new MasonSimulatorPaymentProviderService(properties, null);
    }
}
