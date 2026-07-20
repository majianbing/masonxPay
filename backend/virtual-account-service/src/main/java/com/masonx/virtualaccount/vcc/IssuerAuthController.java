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
 * Rail-sim issuer adapter endpoint: the card-network-sim (issuer {@code RAIL_SIM})
 * calls this for BIN 999999 authorization decisions. Future issuer integrations
 * get their own adapter endpoints that normalize into the same
 * {@link CardAuthorizationService} decision core.
 *
 * <p>The issuer identity is bound to the adapter endpoint/channel — never taken
 * from the request payload.
 *
 * <p>This path is internal — not exposed through the merchant-facing API gateway.
 * In production, this would be network-isolated to the issuer/processor mesh.
 */
@RestController
@RequestMapping("/internal/issuer")
public class IssuerAuthController {

    static final String ISSUER_RAIL_SIM = "RAIL_SIM";

    private final CardAuthorizationService authorizationService;

    public IssuerAuthController(CardAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<IssuerAuthResponse> authorize(@Valid @RequestBody IssuerAuthRequest req) {
        return ResponseEntity.ok(authorizationService.authorize(ISSUER_RAIL_SIM, req));
    }
}
