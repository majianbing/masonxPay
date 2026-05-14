package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.PaymentProvider;

import java.util.UUID;

public record RouteCandidate(ProviderAccount account) {
    public UUID accountId() {
        return account.getId();
    }

    public PaymentProvider provider() {
        return account.getProvider();
    }
}
