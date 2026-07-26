package com.masonx.virtualaccount.issuer;

import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class IssuerCardProviderDispatcher {

    private final Map<IssuerPartnerType, IssuerCardProviderService> providers;

    public IssuerCardProviderDispatcher(List<IssuerCardProviderService> services) {
        this.providers = new EnumMap<>(IssuerPartnerType.class);
        for (IssuerCardProviderService service : services) {
            this.providers.put(service.provider(), service);
        }
    }

    public IssuerCardProviderService require(IssuerPartnerType type) {
        IssuerCardProviderService service = providers.get(type);
        if (service == null) {
            throw new IllegalStateException("No issuer card provider configured for: " + type);
        }
        return service;
    }
}
