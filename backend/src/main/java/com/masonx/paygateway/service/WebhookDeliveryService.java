package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.domain.webhook.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final int MAX_ATTEMPTS = 5;
    // Retry delays (seconds): 30s, 5m, 30m, 2h, 8h
    private static final long[] RETRY_DELAYS_SECONDS = {30, 300, 1800, 7200, 28800};

    private final GatewayEventRepository gatewayEventRepository;
    private final WebhookEndpointRepository webhookEndpointRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookSigningService signingService;
    private final OutboxEventRepository outboxEventRepository;
    private final RestTemplate webhookRestTemplate;
    private final TransactionTemplate txTemplate;
    private final PaymentMetrics metrics;
    private final boolean outboxPollerEnabled;

    public WebhookDeliveryService(GatewayEventRepository gatewayEventRepository,
                                   WebhookEndpointRepository webhookEndpointRepository,
                                   WebhookDeliveryRepository webhookDeliveryRepository,
                                   WebhookSigningService signingService,
                                   OutboxEventRepository outboxEventRepository,
                                   RestTemplate webhookRestTemplate,
                                   PlatformTransactionManager txManager,
                                   PaymentMetrics metrics,
                                   @Value("${app.webhook.outbox-poller.enabled:true}") boolean outboxPollerEnabled) {
        this.gatewayEventRepository = gatewayEventRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.signingService = signingService;
        this.outboxEventRepository = outboxEventRepository;
        this.webhookRestTemplate = webhookRestTemplate;
        this.txTemplate = new TransactionTemplate(txManager);
        this.metrics = metrics;
        this.outboxPollerEnabled = outboxPollerEnabled;
    }

    /**
     * Transactional outbox poller — runs every 5 seconds.
     *
     * Reads unpublished OutboxEvent rows (written atomically with the payment intent save)
     * and fans them out to active merchant webhook endpoints. Each event is processed in its
     * own transaction: GatewayEvent + WebhookDelivery rows are created and the OutboxEvent is
     * marked published atomically, so a JVM crash during delivery cannot drop an event.
     *
     * HTTP delivery happens outside the transaction — failures are handled by retryPending().
     */
    @Scheduled(fixedDelay = 5_000)
    public void processOutbox() {
        if (!outboxPollerEnabled) {
            return;
        }
        List<OutboxEvent> pending = outboxEventRepository
                .findByPublishedFalseOrderByCreatedAtAsc(PageRequest.of(0, 100));
        for (OutboxEvent outboxEvt : pending) {
            try {
                processOutboxEvent(outboxEvt.getId());
            } catch (Exception e) {
                log.warn("Failed to process outbox event {}: {}", outboxEvt.getId(), e.getMessage());
            }
        }
    }

    public void processOutboxEvent(UUID outboxEventId) {
        // Create GatewayEvent + WebhookDelivery rows and mark published — all in one TX
        List<WebhookDelivery> deliveries = txTemplate.execute(ts -> {
            // Re-read with a pessimistic lock. If another node processed this row first,
            // the published=false predicate is rechecked and this consumer becomes a no-op.
            OutboxEvent fresh = outboxEventRepository.findByIdUnpublishedForUpdate(outboxEventId)
                    .orElse(null);
            if (fresh == null) return List.of();

            GatewayEvent event = new GatewayEvent();
            event.setMerchantId(fresh.getMerchantId());
            event.setEventType(fresh.getEventType());
            event.setResourceId(fresh.getResourceId());
            event.setPayload(fresh.getPayload());
            event = gatewayEventRepository.save(event);

            List<WebhookEndpoint> endpoints = webhookEndpointRepository
                    .findAllByMerchantIdAndStatus(fresh.getMerchantId(), WebhookEndpointStatus.ACTIVE);

            List<WebhookDelivery> created = new java.util.ArrayList<>();
            for (WebhookEndpoint endpoint : endpoints) {
                if (!endpoint.getSubscribedEventList().contains(fresh.getEventType())) continue;
                WebhookDelivery delivery = new WebhookDelivery();
                delivery.setGatewayEventId(event.getId());
                delivery.setWebhookEndpointId(endpoint.getId());
                delivery.setNextRetryAt(Instant.now());
                created.add(webhookDeliveryRepository.save(delivery));
            }

            fresh.setPublished(true);
            outboxEventRepository.save(fresh);
            return created;
        });

        if (deliveries != null && !deliveries.isEmpty()) {
            metrics.recordOutboxProcessed();
        }

        // Deliver outside the TX — failures are retried by retryPending()
        if (deliveries != null) {
            for (WebhookDelivery delivery : deliveries) {
                webhookEndpointRepository.findById(delivery.getWebhookEndpointId())
                        .ifPresent(endpoint -> deliver(delivery, endpoint,
                                gatewayEventRepository.findById(delivery.getGatewayEventId())
                                        .map(GatewayEvent::getPayload).orElse("")));
            }
        }
    }

    @Scheduled(fixedDelay = 60_000) // every minute
    @Transactional
    public void retryPending() {
        List<WebhookDelivery> due = webhookDeliveryRepository.findDueForRetry(Instant.now());
        for (WebhookDelivery delivery : due) {
            // Skip deliveries that were just created and already attempted in processOutbox
            if (delivery.getAttemptCount() == 0) continue;

            webhookEndpointRepository.findById(delivery.getWebhookEndpointId()).ifPresent(endpoint ->
                    gatewayEventRepository.findById(delivery.getGatewayEventId()).ifPresent(event ->
                            deliver(delivery, endpoint, event.getPayload())
                    )
            );
        }
    }

    private void deliver(WebhookDelivery delivery, WebhookEndpoint endpoint, String payload) {
        long timestamp = Instant.now().getEpochSecond();
        String signature = signingService.buildSignatureHeader(endpoint.getSigningSecret(), timestamp, payload);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Gateway-Signature", signature);
        headers.set("X-Gateway-Timestamp", String.valueOf(timestamp));

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptedAt(Instant.now());

        try {
            ResponseEntity<String> response = webhookRestTemplate.exchange(
                    endpoint.getUrl(), HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);

            int status = response.getStatusCode().value();
            delivery.setHttpStatus(status);
            delivery.setResponseBody(truncate(response.getBody(), 2000));

            if (status >= 200 && status < 300) {
                delivery.setStatus(WebhookDeliveryStatus.SUCCEEDED);
                delivery.setNextRetryAt(null);
            } else {
                scheduleRetryOrFail(delivery);
            }
        } catch (Exception ex) {
            log.warn("Webhook delivery {} to {} failed: {}", delivery.getId(), endpoint.getUrl(), ex.getMessage());
            delivery.setResponseBody(ex.getMessage());
            scheduleRetryOrFail(delivery);
        }

        webhookDeliveryRepository.save(delivery);
    }

    private void scheduleRetryOrFail(WebhookDelivery delivery) {
        int attempt = delivery.getAttemptCount();
        if (attempt >= MAX_ATTEMPTS) {
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            delivery.setNextRetryAt(null);
        } else {
            long delaySecs = RETRY_DELAYS_SECONDS[Math.min(attempt - 1, RETRY_DELAYS_SECONDS.length - 1)];
            delivery.setStatus(WebhookDeliveryStatus.RETRYING);
            delivery.setNextRetryAt(Instant.now().plusSeconds(delaySecs));
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
