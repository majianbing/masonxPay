package com.masonx.paygateway.domain.merchant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantUserRepository extends JpaRepository<MerchantUser, UUID> {

    Optional<MerchantUser> findByUser_IdAndMerchant_Id(UUID userId, UUID merchantId);

    @Query("SELECT mu FROM MerchantUser mu JOIN FETCH mu.user WHERE mu.merchant.id = :merchantId")
    List<MerchantUser> findAllByMerchantId(UUID merchantId);

    boolean existsByUser_IdAndMerchant_IdAndStatusNot(UUID userId, UUID merchantId, MerchantUserStatus status);
}
