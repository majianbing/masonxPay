package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccountCapability;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAccountCapabilityServiceTest {

    private final ProviderAccountCapabilityService service = new ProviderAccountCapabilityService(null);

    @Test
    void supportsMatchingContextForProviderToken() {
        UUID merchantId = UUID.randomUUID();
        ProviderAccountCapability capability = capability(merchantId);

        assertThat(service.supports(capability, context(merchantId, InstrumentSource.PROVIDER_TOKEN,
                CaptureMethod.AUTOMATIC, "US", "USD", 1500)))
                .isTrue();
    }

    @Test
    void rejectsManualCaptureWhenCapabilityDoesNotSupportIt() {
        UUID merchantId = UUID.randomUUID();
        ProviderAccountCapability capability = capability(merchantId);
        capability.setSupportsManualCapture(false);

        assertThat(service.supports(capability, context(merchantId, InstrumentSource.PROVIDER_TOKEN,
                CaptureMethod.MANUAL, "US", "USD", 1500)))
                .isFalse();
    }

    @Test
    void rejectsUnsupportedVaultToken() {
        UUID merchantId = UUID.randomUUID();
        ProviderAccountCapability capability = capability(merchantId);

        assertThat(service.supports(capability, context(merchantId, InstrumentSource.VAULT_TOKEN,
                CaptureMethod.AUTOMATIC, "US", "USD", 1500)))
                .isFalse();
    }

    @Test
    void rejectsOutOfRangeAmount() {
        UUID merchantId = UUID.randomUUID();
        ProviderAccountCapability capability = capability(merchantId);

        assertThat(service.supports(capability, context(merchantId, InstrumentSource.PROVIDER_TOKEN,
                CaptureMethod.AUTOMATIC, "US", "USD", 5000)))
                .isFalse();
    }

    private ProviderAccountCapability capability(UUID merchantId) {
        ProviderAccountCapability capability = new ProviderAccountCapability();
        capability.setMerchantId(merchantId);
        capability.setProviderAccountId(UUID.randomUUID());
        capability.setPaymentMethodType("card");
        capability.setCountry("US");
        capability.setCurrency("USD");
        capability.setMinAmount(1000L);
        capability.setMaxAmount(2000L);
        capability.setSupportsProviderToken(true);
        capability.setEnabled(true);
        return capability;
    }

    private RoutingContext context(UUID merchantId, InstrumentSource source, CaptureMethod captureMethod,
                                   String country, String currency, long amount) {
        return new RoutingContext(
                merchantId,
                ApiKeyMode.TEST,
                amount,
                currency,
                country,
                "card",
                captureMethod,
                null,
                null,
                Map.of(),
                null,
                source,
                InstrumentPortability.PROVIDER_SCOPED,
                null,
                null,
                null,
                null,
                null
        );
    }
}
