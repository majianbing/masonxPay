package com.masonx.virtualaccount.vcc;

import com.masonx.virtualaccount.vcc.dto.IssuerAuthRequest;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal issuer endpoint called by the rail-simulator's card-network-sim
 * for BIN 999999 (VA-issued VCC) authorization decisions.
 *
 * <p>This path is internal — not exposed through the merchant-facing API gateway.
 * In production, this would be network-isolated to the simulator/acquirer mesh.
 */
@RestController
@RequestMapping("/internal/issuer")
public class IssuerAuthController {

    private final IssuerAuthService issuerAuthService;

    public IssuerAuthController(IssuerAuthService issuerAuthService) {
        this.issuerAuthService = issuerAuthService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<IssuerAuthResponse> authorize(@Valid @RequestBody IssuerAuthRequest req) {
        IssuerAuthResponse response = issuerAuthService.authorize(req);
        return ResponseEntity.ok(response);
    }
}
