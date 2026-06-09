package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.webhook.*;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.web.dto.WebhookDeliveryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookDeliveryServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    private final GatewayEventRepository gatewayEventRepo = mock(GatewayEventRepository.class);
    private final WebhookEndpointRepository endpointRepo = mock(WebhookEndpointRepository.class);
    private final WebhookDeliveryRepository deliveryRepo = mock(WebhookDeliveryRepository.class);
    private final WebhookSigningService signingService = mock(WebhookSigningService.class);
    private final OutboxEventRepository outboxEventRepo = mock(OutboxEventRepository.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final PaymentMetrics metrics = mock(PaymentMetrics.class);

    private final WebhookDeliveryService service = new WebhookDeliveryService(
            gatewayEventRepo, endpointRepo, deliveryRepo, signingService,
            outboxEventRepo, restTemplate, txManager, metrics, false);

    // --- listDeliveries ---

    @Test
    void listDeliveries_noStatusFilter_queriesUnfiltered() {
        WebhookEndpoint endpoint = endpoint();
        WebhookDelivery delivery = delivery(ENDPOINT_ID, EVENT_ID);
        Pageable pageable = PageRequest.of(0, 20);
        Page<WebhookDelivery> page = new PageImpl<>(List.of(delivery), pageable, 1);

        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.of(endpoint));
        when(deliveryRepo.findByWebhookEndpointIdOrderByCreatedAtDesc(ENDPOINT_ID, pageable)).thenReturn(page);

        Page<WebhookDeliveryResponse> result = service.listDeliveries(MERCHANT_ID, ENDPOINT_ID, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).webhookEndpointId()).isEqualTo(ENDPOINT_ID);
        verify(deliveryRepo).findByWebhookEndpointIdOrderByCreatedAtDesc(ENDPOINT_ID, pageable);
        verify(deliveryRepo, never()).findByWebhookEndpointIdAndStatusOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void listDeliveries_withStatusFilter_queriesFiltered() {
        WebhookEndpoint endpoint = endpoint();
        WebhookDelivery delivery = delivery(ENDPOINT_ID, EVENT_ID);
        delivery.setStatus(WebhookDeliveryStatus.FAILED);
        Pageable pageable = PageRequest.of(0, 20);
        Page<WebhookDelivery> page = new PageImpl<>(List.of(delivery), pageable, 1);

        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.of(endpoint));
        when(deliveryRepo.findByWebhookEndpointIdAndStatusOrderByCreatedAtDesc(
                ENDPOINT_ID, WebhookDeliveryStatus.FAILED, pageable)).thenReturn(page);

        Page<WebhookDeliveryResponse> result =
                service.listDeliveries(MERCHANT_ID, ENDPOINT_ID, WebhookDeliveryStatus.FAILED, pageable);

        assertThat(result.getContent().get(0).status()).isEqualTo("FAILED");
        verify(deliveryRepo).findByWebhookEndpointIdAndStatusOrderByCreatedAtDesc(
                ENDPOINT_ID, WebhookDeliveryStatus.FAILED, pageable);
        verify(deliveryRepo, never()).findByWebhookEndpointIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void listDeliveries_endpointNotOwnedByMerchant_throws() {
        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listDeliveries(MERCHANT_ID, ENDPOINT_ID, null, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Webhook endpoint not found");
    }

    // --- replay ---

    @Test
    void replay_createsNewDeliveryAndCallsDeliver() {
        WebhookEndpoint endpoint = endpoint();
        WebhookDelivery original = delivery(ENDPOINT_ID, EVENT_ID);
        original.setStatus(WebhookDeliveryStatus.FAILED);
        GatewayEvent event = gatewayEvent(EVENT_ID, "{\"type\":\"test\"}");

        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.of(endpoint));
        when(deliveryRepo.findById(DELIVERY_ID)).thenReturn(Optional.of(original));
        when(gatewayEventRepo.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(signingService.buildSignatureHeader(any(), anyLong(), any())).thenReturn("t=1,v1=abc");
        when(restTemplate.exchange(any(String.class), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        WebhookDeliveryResponse result = service.replay(MERCHANT_ID, ENDPOINT_ID, DELIVERY_ID);

        assertThat(result.gatewayEventId()).isEqualTo(EVENT_ID);
        assertThat(result.webhookEndpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.attemptCount()).isEqualTo(1);
        // save called twice: initial insert + post-deliver update
        verify(deliveryRepo, times(2)).save(any());
    }

    @Test
    void replay_endpointNotOwnedByMerchant_throws() {
        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(MERCHANT_ID, ENDPOINT_ID, DELIVERY_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Webhook endpoint not found");
    }

    @Test
    void replay_deliveryBelongsToDifferentEndpoint_throws() {
        WebhookEndpoint endpoint = endpoint();
        WebhookDelivery foreignDelivery = delivery(UUID.randomUUID(), EVENT_ID); // different endpointId

        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.of(endpoint));
        when(deliveryRepo.findById(DELIVERY_ID)).thenReturn(Optional.of(foreignDelivery));

        assertThatThrownBy(() -> service.replay(MERCHANT_ID, ENDPOINT_ID, DELIVERY_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Webhook delivery not found");
    }

    @Test
    void replay_providerCallFails_deliveryScheduledForRetry() {
        WebhookEndpoint endpoint = endpoint();
        WebhookDelivery original = delivery(ENDPOINT_ID, EVENT_ID);
        GatewayEvent event = gatewayEvent(EVENT_ID, "{}");

        when(endpointRepo.findByIdAndMerchantId(ENDPOINT_ID, MERCHANT_ID)).thenReturn(Optional.of(endpoint));
        when(deliveryRepo.findById(DELIVERY_ID)).thenReturn(Optional.of(original));
        when(gatewayEventRepo.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(signingService.buildSignatureHeader(any(), anyLong(), any())).thenReturn("t=1,v1=abc");
        when(restTemplate.exchange(any(String.class), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error"));

        WebhookDeliveryResponse result = service.replay(MERCHANT_ID, ENDPOINT_ID, DELIVERY_ID);

        assertThat(result.status()).isIn("RETRYING", "FAILED");
    }

    // --- helpers ---

    private WebhookEndpoint endpoint() {
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setMerchantId(MERCHANT_ID);
        ep.setUrl("https://example.com/webhook");
        ep.setSigningSecret("whsec_test");
        ep.setStatus(WebhookEndpointStatus.ACTIVE);
        return ep;
    }

    private WebhookDelivery delivery(UUID endpointId, UUID eventId) {
        WebhookDelivery d = new WebhookDelivery();
        d.setWebhookEndpointId(endpointId);
        d.setGatewayEventId(eventId);
        return d;
    }

    private GatewayEvent gatewayEvent(UUID id, String payload) {
        GatewayEvent e = new GatewayEvent();
        e.setMerchantId(MERCHANT_ID);
        e.setEventType("payment_intent.succeeded");
        e.setPayload(payload);
        return e;
    }
}
