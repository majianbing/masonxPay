package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReusablePaymentMethodDispatcherTest {

    @Test
    void setup_dispatchesToMatchingProvider() {
        ReusablePaymentMethodProviderService mockService = mock(ReusablePaymentMethodProviderService.class);
        when(mockService.brand()).thenReturn(PaymentProvider.SIMULATOR);
        ReusablePaymentMethodSetupRequest request = request();
        SimulatorCredentials creds = new SimulatorCredentials(true, 1.0);
        ReusablePaymentMethodSetupResult expected = ReusablePaymentMethodSetupResult.succeeded("cus_1", "pm_1", "{}");
        when(mockService.setupReusablePaymentMethod(request, creds)).thenReturn(expected);

        ReusablePaymentMethodDispatcher dispatcher = new ReusablePaymentMethodDispatcher(List.of(mockService));
        ReusablePaymentMethodSetupResult result = dispatcher.setup(PaymentProvider.SIMULATOR, request, creds);

        assertThat(result).isEqualTo(expected);
        verify(mockService).setupReusablePaymentMethod(request, creds);
    }

    @Test
    void setup_unknownProvider_throwsIllegalStateException() {
        ReusablePaymentMethodProviderService mockService = mock(ReusablePaymentMethodProviderService.class);
        when(mockService.brand()).thenReturn(PaymentProvider.SIMULATOR);

        ReusablePaymentMethodDispatcher dispatcher = new ReusablePaymentMethodDispatcher(List.of(mockService));

        assertThatThrownBy(() -> dispatcher.setup(PaymentProvider.STRIPE, request(), new SimulatorCredentials(true, 1.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE");
    }

    private ReusablePaymentMethodSetupRequest request() {
        return new ReusablePaymentMethodSetupRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "card", "pm_tok_123", null, "idem-dispatcher-1", null, null);
    }
}
