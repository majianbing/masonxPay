package com.masonx.paygateway.domain.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, UUID> {
    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
