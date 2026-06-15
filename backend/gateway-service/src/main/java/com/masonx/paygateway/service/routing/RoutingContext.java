package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import java.util.Map;
import java.util.UUID;

public record RoutingContext(
        UUID merchantId,
        ApiKeyMode mode,
        long amount,
        String currency,
        String country,
        String paymentMethodType,
        CaptureMethod captureMethod,
        UUID customerId,
        String orderId,
        Map<String, String> metadata,
        UUID instrumentId,
        InstrumentSource instrumentSource,
        InstrumentPortability instrumentPortability,
        String cardBrand,
        String binCountry,
        String issuerCountry,
        String cardType,
        String walletType
) {
}
