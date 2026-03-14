package com.masonx.paygateway.domain.connector;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, UUID> {

    List<ProviderAccount> findAllByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    Optional<ProviderAccount> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<ProviderAccount> findByMerchantIdAndProviderAndPrimaryTrueAndStatus(
            UUID merchantId, PaymentProvider provider, ProviderAccountStatus status);

    /** Clear primary flag on all accounts for a merchant+provider before setting a new primary. */
    @Modifying
    @Query("UPDATE ProviderAccount a SET a.primary = false WHERE a.merchantId = :merchantId AND a.provider = :provider")
    void clearPrimaryForProvider(UUID merchantId, PaymentProvider provider);
}
