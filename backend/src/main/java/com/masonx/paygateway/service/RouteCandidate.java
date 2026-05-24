package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.PaymentProvider;

import java.util.Map;
import java.util.UUID;

public record RouteCandidate(ProviderAccount account, Map<String, String> outcomeActions) {
    public RouteCandidate(ProviderAccount account) {
        this(account, Map.of());
    }

    public RouteCandidate {
        outcomeActions = outcomeActions != null ? Map.copyOf(outcomeActions) : Map.of();
    }

    public UUID accountId() {
        return account.getId();
    }

    public PaymentProvider provider() {
        return account.getProvider();
    }
}
