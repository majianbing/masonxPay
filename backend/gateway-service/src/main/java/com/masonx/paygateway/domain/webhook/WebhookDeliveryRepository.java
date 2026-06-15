package com.masonx.paygateway.domain.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findAllByGatewayEventId(UUID gatewayEventId);

    Page<WebhookDelivery> findByWebhookEndpointIdOrderByCreatedAtDesc(UUID webhookEndpointId, Pageable pageable);
    Page<WebhookDelivery> findByWebhookEndpointIdAndStatusOrderByCreatedAtDesc(UUID webhookEndpointId, WebhookDeliveryStatus status, Pageable pageable);

    @Query("SELECT d FROM WebhookDelivery d WHERE d.status IN ('PENDING', 'RETRYING') AND d.nextRetryAt <= :now ORDER BY d.nextRetryAt ASC")
    List<WebhookDelivery> findDueForRetry(Instant now);
}
