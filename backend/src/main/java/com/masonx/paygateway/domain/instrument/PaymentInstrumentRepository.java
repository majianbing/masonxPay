package com.masonx.paygateway.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentInstrumentRepository extends JpaRepository<PaymentInstrument, UUID> {
    Optional<PaymentInstrument> findByIdAndMerchantId(UUID id, UUID merchantId);
    List<PaymentInstrument> findAllByMerchantIdAndCustomerId(UUID merchantId, UUID customerId);
    Optional<PaymentInstrument> findFirstByMerchantIdAndCustomerIdAndProviderAndProviderAccountIdAndProviderCustomerReferenceIsNotNullOrderByCreatedAtDesc(
            UUID merchantId, UUID customerId, PaymentProvider provider, UUID providerAccountId);
}
