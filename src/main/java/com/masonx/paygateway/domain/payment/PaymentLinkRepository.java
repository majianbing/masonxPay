package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, UUID> {
    List<PaymentLink> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    Optional<PaymentLink> findByToken(String token);
    Optional<PaymentLink> findByIdAndMerchantId(UUID id, UUID merchantId);
}
