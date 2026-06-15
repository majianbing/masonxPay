package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.service.RoutePlan;
import com.masonx.paygateway.service.RoutingEngine;
import com.masonx.paygateway.web.dto.SimulateRouteRequest;
import com.masonx.paygateway.web.dto.SimulateRouteResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RouteSimulationService {

    private final RoutingEngine routingEngine;

    public RouteSimulationService(RoutingEngine routingEngine) {
        this.routingEngine = routingEngine;
    }

    @Transactional(readOnly = true)
    public SimulateRouteResponse simulate(UUID merchantId, SimulateRouteRequest request) {
        ApiKeyMode mode = request.mode() != null
                ? ApiKeyMode.valueOf(request.mode().toUpperCase())
                : ApiKeyMode.TEST;
        RoutingContext context = new RoutingContext(
                merchantId,
                mode,
                request.amount(),
                request.currency().toUpperCase(),
                normalizeOptional(request.country()),
                request.paymentMethodType() != null ? request.paymentMethodType() : "card",
                captureMethod(request.captureMethod()),
                request.customerId(),
                request.orderId(),
                request.metadata() != null ? request.metadata() : Map.of(),
                request.instrumentId(),
                enumValue(InstrumentSource.class, request.instrumentSource()),
                enumValue(InstrumentPortability.class, request.instrumentPortability()),
                request.cardBrand(),
                normalizeOptional(request.binCountry()),
                normalizeOptional(request.issuerCountry()),
                request.cardType(),
                request.walletType()
        );

        Optional<RoutePlan> plan = routingEngine.resolvePlan(context);
        return plan.map(routePlan -> SimulateRouteResponse.matched(merchantId, mode.name(), routePlan))
                .orElseGet(() -> SimulateRouteResponse.empty(merchantId, mode.name()));
    }

    private CaptureMethod captureMethod(String value) {
        return value != null ? CaptureMethod.valueOf(value.toUpperCase()) : CaptureMethod.AUTOMATIC;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value != null && !value.isBlank() ? Enum.valueOf(type, value.toUpperCase()) : null;
    }

    private String normalizeOptional(String value) {
        return value != null && !value.isBlank() ? value.toUpperCase() : null;
    }
}
