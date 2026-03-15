package com.masonx.paygateway.domain.connector;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, UUID> {

    List<ProviderAccount> findAllByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);

    Optional<ProviderAccount> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<ProviderAccount> findByMerchantIdAndProviderAndModeAndPrimaryTrueAndStatus(
            UUID merchantId, PaymentProvider provider, ApiKeyMode mode, ProviderAccountStatus status);

    /** Clear primary flag for a given merchant + provider + mode before setting a new primary. */
    @Modifying
    @Query("UPDATE ProviderAccount a SET a.primary = false WHERE a.merchantId = :merchantId AND a.provider = :provider AND a.mode = :mode")
    void clearPrimaryForProvider(UUID merchantId, PaymentProvider provider, ApiKeyMode mode);
}
