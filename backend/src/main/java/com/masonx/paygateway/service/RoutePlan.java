package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.connector.ProviderAccount;

import java.util.ArrayList;
import java.util.List;

public record RoutePlan(List<RouteCandidate> candidates) {
    public RoutePlan {
        candidates = List.copyOf(candidates);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Route plan must include at least one candidate");
        }
    }

    public static RoutePlan single(ProviderAccount account) {
        return new RoutePlan(List.of(new RouteCandidate(account)));
    }

    public static RoutePlan from(RoutingEngine.RoutingResult result) {
        List<RouteCandidate> candidates = new ArrayList<>();
        candidates.add(new RouteCandidate(result.primary()));
        if (result.hasFallback()) {
            candidates.add(new RouteCandidate(result.fallback()));
        }
        return new RoutePlan(candidates);
    }

    public RouteCandidate first() {
        return candidates.get(0);
    }
}
