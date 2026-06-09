package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.webhook.WebhookDeliveryStatus;
import com.masonx.paygateway.service.WebhookDeliveryService;
import com.masonx.paygateway.service.WebhookEndpointService;
import com.masonx.paygateway.web.dto.CreateWebhookEndpointRequest;
import com.masonx.paygateway.web.dto.UpdateWebhookEndpointRequest;
import com.masonx.paygateway.web.dto.WebhookDeliveryResponse;
import com.masonx.paygateway.web.dto.WebhookEndpointResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/webhook-endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService webhookEndpointService;
    private final WebhookDeliveryService webhookDeliveryService;

    public WebhookEndpointController(WebhookEndpointService webhookEndpointService,
                                      WebhookDeliveryService webhookDeliveryService) {
        this.webhookEndpointService = webhookEndpointService;
        this.webhookDeliveryService = webhookDeliveryService;
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
    public ResponseEntity<Page<WebhookDeliveryResponse>> listDeliveries(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @RequestParam(required = false) WebhookDeliveryStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(webhookDeliveryService.listDeliveries(merchantId, endpointId, status, pageable));
    }

    @PostMapping("/{endpointId}/deliveries/{deliveryId}/replay")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'WEBHOOK', 'UPDATE')")
    public ResponseEntity<WebhookDeliveryResponse> replay(
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @PathVariable UUID deliveryId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(webhookDeliveryService.replay(merchantId, endpointId, deliveryId));
    }
}
