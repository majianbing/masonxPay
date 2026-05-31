package com.masonx.paygateway.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerPaymentMethodRepository extends JpaRepository<CustomerPaymentMethod, UUID> {
    List<CustomerPaymentMethod> findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(UUID merchantId, UUID customerId);
    Optional<CustomerPaymentMethod> findByIdAndMerchantIdAndCustomerId(UUID id, UUID merchantId, UUID customerId);
    Optional<CustomerPaymentMethod> findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
            UUID merchantId, UUID customerId, UUID paymentInstrumentId);

    @Modifying
    @Transactional
    @Query("""
        UPDATE CustomerPaymentMethod m
        SET m.defaultMethod = false
        WHERE m.merchantId = :merchantId
          AND m.customerId = :customerId
          AND m.defaultMethod = true
        """)
    void clearDefault(@Param("merchantId") UUID merchantId, @Param("customerId") UUID customerId);
}
