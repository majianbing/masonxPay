package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.webhook.*;
import com.masonx.paygateway.web.dto.CreateWebhookEndpointRequest;
import com.masonx.paygateway.web.dto.UpdateWebhookEndpointRequest;
import com.masonx.paygateway.web.dto.WebhookEndpointResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class WebhookEndpointService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WebhookEndpointRepository webhookEndpointRepository;

    public WebhookEndpointService(WebhookEndpointRepository webhookEndpointRepository) {
        this.webhookEndpointRepository = webhookEndpointRepository;
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpointResponse> list(UUID merchantId) {
        return webhookEndpointRepository.findAllByMerchantId(merchantId)
                .stream().map(WebhookEndpointResponse::from).toList();
    }

    public WebhookEndpointResponse create(UUID merchantId, CreateWebhookEndpointRequest req) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setMerchantId(merchantId);
        endpoint.setUrl(req.url());
        endpoint.setDescription(req.description());
        endpoint.setSigningSecret(generateSigningSecret());
        if (req.subscribedEvents() != null && !req.subscribedEvents().isEmpty()) {
            endpoint.setSubscribedEventList(req.subscribedEvents());
        }
        return WebhookEndpointResponse.from(webhookEndpointRepository.save(endpoint));
    }

    public WebhookEndpointResponse update(UUID merchantId, UUID endpointId, UpdateWebhookEndpointRequest req) {
        WebhookEndpoint endpoint = load(merchantId, endpointId);
        if (req.url() != null) endpoint.setUrl(req.url());
        if (req.description() != null) endpoint.setDescription(req.description());
        if (req.subscribedEvents() != null) endpoint.setSubscribedEventList(req.subscribedEvents());
        if (req.status() != null) endpoint.setStatus(WebhookEndpointStatus.valueOf(req.status().toUpperCase()));
        return WebhookEndpointResponse.from(webhookEndpointRepository.save(endpoint));
    }

    public void delete(UUID merchantId, UUID endpointId) {
        WebhookEndpoint endpoint = load(merchantId, endpointId);
        webhookEndpointRepository.delete(endpoint);
    }

    public WebhookEndpointResponse rotateSecret(UUID merchantId, UUID endpointId) {
        WebhookEndpoint endpoint = load(merchantId, endpointId);
        endpoint.setSigningSecret(generateSigningSecret());
        return WebhookEndpointResponse.from(webhookEndpointRepository.save(endpoint));
    }

    private WebhookEndpoint load(UUID merchantId, UUID endpointId) {
        return webhookEndpointRepository.findByIdAndMerchantId(endpointId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook endpoint not found"));
    }

    private String generateSigningSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }
}
