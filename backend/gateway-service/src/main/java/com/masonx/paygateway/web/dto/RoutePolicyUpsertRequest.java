package com.masonx.paygateway.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record RoutePolicyUpsertRequest(
        @NotBlank String mode,
        @NotBlank String name,
        String description,
        @NotEmpty List<@Valid RouteRequest> routes
) {
    public record RouteRequest(
            @NotBlank String name,
            int routeOrder,
            boolean defaultRoute,
            @NotBlank String conditionsJson,
            @NotEmpty List<@Valid StepRequest> steps
    ) {
    }

    public record StepRequest(
            int stepOrder,
            @NotNull UUID providerAccountId,
            int trafficWeight,
            Integer maxCostBps,
            Boolean skipIfDegraded,
            @NotBlank String outcomeActionsJson
    ) {
    }
}
