package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.connector.ProviderAccountCapability;
import com.masonx.paygateway.domain.connector.ProviderAccountCapabilityRepository;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProviderAccountCapabilityService {

    private final ProviderAccountCapabilityRepository repository;

    public ProviderAccountCapabilityService(ProviderAccountCapabilityRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean supports(UUID merchantId, UUID providerAccountId, RoutingContext context) {
        return repository.findAllByMerchantIdAndProviderAccountIdAndEnabledTrue(merchantId, providerAccountId)
                .stream()
                .anyMatch(capability -> supports(capability, context));
    }

    @Transactional(readOnly = true)
    public boolean supportsOrUnspecified(UUID merchantId, UUID providerAccountId, RoutingContext context) {
        List<ProviderAccountCapability> capabilities =
                repository.findAllByMerchantIdAndProviderAccountIdAndEnabledTrue(merchantId, providerAccountId);
        if (capabilities.isEmpty()) {
            return true;
        }
        return capabilities.stream().anyMatch(capability -> supports(capability, context));
    }

    public boolean supports(ProviderAccountCapability capability, RoutingContext context) {
        if (capability == null || context == null || !capability.isEnabled()) {
            return false;
        }
        return same(capability.getMerchantId(), context.merchantId())
                && matchesText(capability.getPaymentMethodType(), context.paymentMethodType())
                && matchesOptionalText(capability.getCountry(), context.country())
                && matchesOptionalText(capability.getCurrency(), context.currency())
                && matchesAmount(capability, context.amount())
                && matchesCapture(capability, context.captureMethod())
                && matchesInstrumentSource(capability, context.instrumentSource());
    }

    private boolean matchesAmount(ProviderAccountCapability capability, long amount) {
        return (capability.getMinAmount() == null || amount >= capability.getMinAmount())
                && (capability.getMaxAmount() == null || amount <= capability.getMaxAmount());
    }

    private boolean matchesCapture(ProviderAccountCapability capability, CaptureMethod captureMethod) {
        return captureMethod != CaptureMethod.MANUAL || capability.isSupportsManualCapture();
    }

    private boolean matchesInstrumentSource(ProviderAccountCapability capability, InstrumentSource source) {
        if (source == null) {
            return true;
        }
        return switch (source) {
            case PROVIDER_TOKEN -> capability.isSupportsProviderToken();
            case VAULT_TOKEN -> capability.isSupportsVaultToken();
            case NETWORK_TOKEN -> capability.isSupportsNetworkToken();
            case WALLET_TOKEN, LOCAL_PAYMENT_HANDLE -> true;
        };
    }

    private boolean matchesText(String expected, String actual) {
        return expected != null && actual != null
                && normalize(expected).equals(normalize(actual));
    }

    private boolean matchesOptionalText(String expected, String actual) {
        return expected == null || expected.isBlank() || matchesText(expected, actual);
    }

    private boolean same(UUID expected, UUID actual) {
        return expected != null && expected.equals(actual);
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
