package com.masonx.paygateway.domain.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {
    List<WebhookEndpoint> findAllByMerchantId(UUID merchantId);
    Optional<WebhookEndpoint> findByIdAndMerchantId(UUID id, UUID merchantId);
    List<WebhookEndpoint> findAllByMerchantIdAndStatus(UUID merchantId, WebhookEndpointStatus status);
}
