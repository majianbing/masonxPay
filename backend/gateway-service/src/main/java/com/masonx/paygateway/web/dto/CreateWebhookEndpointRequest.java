package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

import java.util.List;

public record CreateWebhookEndpointRequest(
        @NotBlank @URL String url,
        String description,
        List<String> subscribedEvents
) {}
