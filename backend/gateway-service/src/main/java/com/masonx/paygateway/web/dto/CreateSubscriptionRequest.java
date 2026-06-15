package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateSubscriptionRequest(
        @NotNull UUID customerId,
        @NotBlank String currency,
        @NotNull BillingIntervalUnit intervalUnit,
        @Min(1) int intervalCount,
        @Min(0) Integer trialDays,
        Map<String, String> metadata,
        @NotEmpty List<@Valid SubscriptionItemRequest> items
) {
    public record SubscriptionItemRequest(
            @NotBlank String description,
            @Min(50) long amount,
            @Min(1) int quantity
    ) {}
}
