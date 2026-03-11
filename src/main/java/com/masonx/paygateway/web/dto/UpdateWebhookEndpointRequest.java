package com.masonx.paygateway.web.dto;

import org.hibernate.validator.constraints.URL;

import java.util.List;

public record UpdateWebhookEndpointRequest(
        @URL String url,
        String description,
        List<String> subscribedEvents,
        String status
) {}
