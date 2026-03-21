package com.masonx.paygateway.domain.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findAllByGatewayEventId(UUID gatewayEventId);
    List<WebhookDelivery> findTop50ByWebhookEndpointIdOrderByCreatedAtDesc(UUID webhookEndpointId);

    @Query("SELECT d FROM WebhookDelivery d WHERE d.status IN ('PENDING', 'RETRYING') AND d.nextRetryAt <= :now ORDER BY d.nextRetryAt ASC")
    List<WebhookDelivery> findDueForRetry(Instant now);
}
