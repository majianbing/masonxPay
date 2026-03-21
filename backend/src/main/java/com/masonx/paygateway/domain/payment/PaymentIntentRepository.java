package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<PaymentIntent> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);
    List<PaymentIntent> findByMerchantId(UUID merchantId);
    Page<PaymentIntent> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<PaymentIntent> findByMerchantIdAndStatus(UUID merchantId, PaymentIntentStatus status, Pageable pageable);
    Page<PaymentIntent> findByMerchantIdAndMode(UUID merchantId, ApiKeyMode mode, Pageable pageable);
    Page<PaymentIntent> findByMerchantIdAndStatusAndMode(UUID merchantId, PaymentIntentStatus status, ApiKeyMode mode, Pageable pageable);
    Optional<PaymentIntent> findByProviderPaymentId(String providerPaymentId);
}
