package com.masonx.rail.web;

import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.rail.canonical.BankAccountRef;
import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.CardToken;
import com.masonx.rail.canonical.RailResponse;
import com.masonx.rail.router.RailRouter;
import com.masonx.rail.web.dto.AuthorizeRequest;
import com.masonx.rail.web.dto.AuthorizeResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/rail")
public class RailPaymentController {

    private static final Logger log = LoggerFactory.getLogger(RailPaymentController.class);

    private final RailRouter router;

    public RailPaymentController(RailRouter router) {
        this.router = router;
    }

    /**
     * Card authorization over ISO 8583. MR0: returns 501 until MR1 wires the adapter.
     */
    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(@Valid @RequestBody AuthorizeRequest req) {
        String paymentId = "rp_" + UUID.randomUUID().toString().replace("-", "");
        log.info("Rail authorize request paymentId={} merchant={}", paymentId, req.merchantId());

        String network = resolveNetwork(req.network(), req.testPan());
        var command = new CanonicalPaymentCommand(
                paymentId,
                req.merchantId(),
                req.idempotencyKey(),
                PaymentRail.CARD_ISO8583,
                MoneyMovementType.CARD_AUTH,
                req.amount(),
                req.currency(),
                new CardToken(req.testPan(), req.expiry(), network),
                null,
                null,
                null,
                Map.of("network", network)
        );

        RailResponse response = router.route(command);
        return ResponseEntity.ok(new AuthorizeResponse(
                response.railPaymentId(),
                response.status(),
                response.authCode(),
                response.responseCode(),
                response.networkRef(),
                response.failureReason()
        ));
    }

    /**
     * Bank credit transfer over ISO 20022 (pain.001). MR0: returns 501 until MR3 wires the adapter.
     */
    @PostMapping("/bank-transfers")
    public ResponseEntity<Void> initiateBankTransfer() {
        return ResponseEntity.status(501).build();
    }

    private String resolveNetwork(String explicit, String pan) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.toUpperCase();
        }
        // Derive from BIN: 4-prefix → VISA_SIM, 5-prefix → MC_SIM
        if (pan != null && pan.startsWith("4")) {
            return "VISA_SIM";
        }
        return "MC_SIM";
    }
}
