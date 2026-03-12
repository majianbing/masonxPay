package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.webhook.WebhookDeliveryRepository;
import com.masonx.paygateway.service.WebhookEndpointService;
import com.masonx.paygateway.web.dto.CreateWebhookEndpointRequest;
import com.masonx.paygateway.web.dto.UpdateWebhookEndpointRequest;
import com.masonx.paygateway.web.dto.WebhookDeliveryResponse;
import com.masonx.paygateway.web.dto.WebhookEndpointResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/webhook-endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService webhookEndpointService;
    private final WebhookDeliveryRepository webhookDeliveryRepository;

    public WebhookEndpointController(WebhookEndpointService webhookEndpointService,
                                      WebhookDeliveryRepository webhookDeliveryRepository) {
        this.webhookEndpointService = webhookEndpointService;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'READ')")
    public ResponseEntity<List<WebhookEndpointResponse>> list(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(webhookEndpointService.list(merchantId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'CREATE')")
    public ResponseEntity<WebhookEndpointResponse> create(@PathVariable UUID merchantId,
                                                           @Valid @RequestBody CreateWebhookEndpointRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhookEndpointService.create(merchantId, req));
    }

    @PatchMapping("/{endpointId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'UPDATE')")
    public ResponseEntity<WebhookEndpointResponse> update(@PathVariable UUID merchantId,
                                                           @PathVariable UUID endpointId,
                                                           @Valid @RequestBody UpdateWebhookEndpointRequest req) {
        return ResponseEntity.ok(webhookEndpointService.update(merchantId, endpointId, req));
    }

    @DeleteMapping("/{endpointId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID merchantId, @PathVariable UUID endpointId) {
        webhookEndpointService.delete(merchantId, endpointId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{endpointId}/rotate-secret")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'UPDATE')")
    public ResponseEntity<WebhookEndpointResponse> rotateSecret(@PathVariable UUID merchantId,
                                                                  @PathVariable UUID endpointId) {
        return ResponseEntity.ok(webhookEndpointService.rotateSecret(merchantId, endpointId));
    }

    @GetMapping("/{endpointId}/deliveries")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'READ')")
    public ResponseEntity<List<WebhookDeliveryResponse>> listDeliveries(@PathVariable UUID merchantId,
                                                                         @PathVariable UUID endpointId) {
        List<WebhookDeliveryResponse> deliveries = webhookDeliveryRepository
                .findTop50ByWebhookEndpointIdOrderByCreatedAtDesc(endpointId)
                .stream()
                .map(WebhookDeliveryResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(deliveries);
    }
}
