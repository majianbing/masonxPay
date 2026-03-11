package com.masonx.paygateway.domain.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findAllByMerchantId(UUID merchantId);
    Optional<ApiKey> findByKeyHash(String keyHash);
}
