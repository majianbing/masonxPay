package com.masonx.paygateway.domain.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(UUID merchantId, UUID subscriptionId);
    List<Invoice> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    Optional<Invoice> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<Invoice> findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
            UUID merchantId, UUID subscriptionId, Instant periodStart, Instant periodEnd);
}
