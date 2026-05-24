package com.masonx.paygateway.web;

import com.masonx.paygateway.service.routing.RouteSimulationService;
import com.masonx.paygateway.web.dto.SimulateRouteRequest;
import com.masonx.paygateway.web.dto.SimulateRouteResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/route-simulations")
public class RouteSimulationController {

    private final RouteSimulationService service;

    public RouteSimulationController(RouteSimulationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'READ')")
    public ResponseEntity<SimulateRouteResponse> simulate(@PathVariable UUID merchantId,
                                                          @Valid @RequestBody SimulateRouteRequest request) {
        return ResponseEntity.ok(service.simulate(merchantId, request));
    }
}
