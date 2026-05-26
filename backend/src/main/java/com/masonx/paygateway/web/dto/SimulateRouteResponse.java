package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.service.RouteCandidate;
import com.masonx.paygateway.service.RoutePlan;

import java.util.List;
import java.util.UUID;

public record SimulateRouteResponse(
        UUID merchantId,
        String mode,
        boolean matched,
        List<Candidate> candidates
) {
    public static SimulateRouteResponse matched(UUID merchantId, String mode, RoutePlan plan) {
        return new SimulateRouteResponse(
                merchantId,
                mode,
                true,
                plan.candidates().stream().map(Candidate::from).toList()
        );
    }

    public static SimulateRouteResponse empty(UUID merchantId, String mode) {
        return new SimulateRouteResponse(merchantId, mode, false, List.of());
    }

    public record Candidate(
            UUID accountId,
            String provider,
            String label,
            String status,
            boolean primary,
            int weight
    ) {
        static Candidate from(RouteCandidate candidate) {
            return new Candidate(
                    candidate.accountId(),
                    candidate.provider().name(),
                    candidate.account().getLabel(),
                    candidate.account().getStatus().name(),
                    candidate.account().isPrimary(),
                    candidate.account().getWeight()
            );
        }
    }
}
