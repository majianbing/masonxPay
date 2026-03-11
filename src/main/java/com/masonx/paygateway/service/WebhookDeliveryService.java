package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.webhook.*;
import com.masonx.paygateway.event.PaymentGatewayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

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
    private final RestTemplate webhookRestTemplate;

    public WebhookDeliveryService(GatewayEventRepository gatewayEventRepository,
                                   WebhookEndpointRepository webhookEndpointRepository,
                                   WebhookDeliveryRepository webhookDeliveryRepository,
                                   WebhookSigningService signingService,
                                   RestTemplate webhookRestTemplate) {
        this.gatewayEventRepository = gatewayEventRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.signingService = signingService;
        this.webhookRestTemplate = webhookRestTemplate;
    }

    @Async("webhookExecutor")
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentEvent(PaymentGatewayEvent springEvent) {
        // Persist gateway event
        GatewayEvent event = new GatewayEvent();
        event.setMerchantId(springEvent.getMerchantId());
        event.setEventType(springEvent.getEventType());
        event.setResourceId(springEvent.getResourceId());
        event.setPayload(springEvent.getPayload());
        event = gatewayEventRepository.save(event);

        // Fan out to active endpoints that subscribe to this event
        List<WebhookEndpoint> endpoints = webhookEndpointRepository
                .findAllByMerchantIdAndStatus(springEvent.getMerchantId(), WebhookEndpointStatus.ACTIVE);

        for (WebhookEndpoint endpoint : endpoints) {
            if (!endpoint.getSubscribedEventList().contains(springEvent.getEventType())) continue;

            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setGatewayEventId(event.getId());
            delivery.setWebhookEndpointId(endpoint.getId());
            delivery.setNextRetryAt(Instant.now()); // deliver immediately
            webhookDeliveryRepository.save(delivery);

            deliver(delivery, endpoint, event.getPayload());
        }
    }

    @Scheduled(fixedDelay = 60_000) // every minute
    @Transactional
    public void retryPending() {
        List<WebhookDelivery> due = webhookDeliveryRepository.findDueForRetry(Instant.now());
        for (WebhookDelivery delivery : due) {
            // Skip deliveries that were just created and already attempted in onPaymentEvent
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
