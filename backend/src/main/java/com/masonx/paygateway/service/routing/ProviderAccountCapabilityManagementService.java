package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.connector.ProviderAccountCapability;
import com.masonx.paygateway.domain.connector.ProviderAccountCapabilityRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.web.dto.ProviderAccountCapabilityRequest;
import com.masonx.paygateway.web.dto.ProviderAccountCapabilityResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProviderAccountCapabilityManagementService {

    private final ProviderAccountRepository providerAccountRepository;
    private final ProviderAccountCapabilityRepository capabilityRepository;

    public ProviderAccountCapabilityManagementService(ProviderAccountRepository providerAccountRepository,
                                                      ProviderAccountCapabilityRepository capabilityRepository) {
        this.providerAccountRepository = providerAccountRepository;
        this.capabilityRepository = capabilityRepository;
    }

    @Transactional(readOnly = true)
    public List<ProviderAccountCapabilityResponse> list(UUID merchantId, UUID providerAccountId) {
        verifyProviderAccount(merchantId, providerAccountId);
        return capabilityRepository
                .findAllByMerchantIdAndProviderAccountIdOrderByCreatedAtAsc(merchantId, providerAccountId)
                .stream()
                .map(ProviderAccountCapabilityResponse::from)
                .toList();
    }

    @Transactional
    public List<ProviderAccountCapabilityResponse> replace(UUID merchantId, UUID providerAccountId,
                                                           List<ProviderAccountCapabilityRequest> requests) {
        verifyProviderAccount(merchantId, providerAccountId);
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one capability is required");
        }

        List<ProviderAccountCapability> existing =
                capabilityRepository.findAllByMerchantIdAndProviderAccountIdOrderByCreatedAtAsc(
                        merchantId, providerAccountId);
        capabilityRepository.deleteAll(existing);

        List<ProviderAccountCapability> saved = capabilityRepository.saveAll(requests.stream()
                .map(request -> capability(merchantId, providerAccountId, request))
                .toList());
        return saved.stream().map(ProviderAccountCapabilityResponse::from).toList();
    }

    private void verifyProviderAccount(UUID merchantId, UUID providerAccountId) {
        providerAccountRepository.findByIdAndMerchantId(providerAccountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));
    }

    private ProviderAccountCapability capability(UUID merchantId, UUID providerAccountId,
                                                 ProviderAccountCapabilityRequest request) {
        validateAmountRange(request);
        ProviderAccountCapability capability = new ProviderAccountCapability();
        capability.setMerchantId(merchantId);
        capability.setProviderAccountId(providerAccountId);
        capability.setPaymentMethodType(normalizeRequired(request.paymentMethodType(), "paymentMethodType"));
        capability.setCountry(normalizeOptional(request.country()));
        capability.setCurrency(normalizeOptional(request.currency()));
        capability.setMinAmount(request.minAmount());
        capability.setMaxAmount(request.maxAmount());
        capability.setSupportsManualCapture(request.supportsManualCapture());
        capability.setSupportsRefund(request.supportsRefund());
        capability.setSupportsPartialRefund(request.supportsPartialRefund());
        capability.setSupports3ds(request.supports3ds());
        capability.setSupportsRedirect(request.supportsRedirect());
        capability.setSupportsProviderToken(request.supportsProviderToken());
        capability.setSupportsVaultToken(request.supportsVaultToken());
        capability.setSupportsNetworkToken(request.supportsNetworkToken());
        capability.setSupportsInstallments(request.supportsInstallments());
        capability.setEnabled(request.enabled());
        return capability;
    }

    private void validateAmountRange(ProviderAccountCapabilityRequest request) {
        if (request.minAmount() != null && request.minAmount() < 0) {
            throw new IllegalArgumentException("minAmount must be non-negative");
        }
        if (request.maxAmount() != null && request.maxAmount() < 0) {
            throw new IllegalArgumentException("maxAmount must be non-negative");
        }
        if (request.minAmount() != null && request.maxAmount() != null
                && request.minAmount() > request.maxAmount()) {
            throw new IllegalArgumentException("minAmount must be less than or equal to maxAmount");
        }
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
