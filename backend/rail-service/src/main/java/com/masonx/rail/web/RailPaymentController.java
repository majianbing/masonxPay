package com.masonx.rail.web;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.rail.canonical.BankAccountRef;
import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.CardToken;
import com.masonx.rail.canonical.RailResponse;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.rail.service.RailPaymentService;
import com.masonx.rail.web.dto.AuthorizeRequest;
import com.masonx.rail.web.dto.AuthorizeResponse;
import com.masonx.rail.web.dto.BankTransferRequest;
import com.masonx.rail.web.dto.BankTransferResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/rail")
public class RailPaymentController {

    private static final Logger log = LoggerFactory.getLogger(RailPaymentController.class);

    private final RailPaymentService    paymentService;
    private final SnowflakeIdGenerator  idGen;

    public RailPaymentController(RailPaymentService paymentService, SnowflakeIdGenerator idGen) {
        this.paymentService = paymentService;
        this.idGen          = idGen;
    }

    /** Card authorization over ISO 8583 — MR1. */
    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(@Valid @RequestBody AuthorizeRequest req) {
        String paymentId = idGen.generate(MasonXIdPrefix.RAIL_PAYMENT.prefix());
        log.info("Rail authorize request paymentId={} merchant={}", paymentId, req.merchantId());

        String network = resolveNetwork(req.network(), req.testPan());
        var command = new CanonicalPaymentCommand(
                paymentId,
                req.merchantId(),
                "TEST",
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

        RailResponse response = paymentService.authorize(command);
        return ResponseEntity.ok(new AuthorizeResponse(
                response.railPaymentId(),
                response.status(),
                response.authCode(),
                response.responseCode(),
                response.networkRef(),
                response.failureReason()
        ));
    }

    /** Bank credit transfer over ISO 20022 (pain.001) — MR3. */
    @PostMapping("/bank-transfers")
    public ResponseEntity<BankTransferResponse> initiateBankTransfer(
            @Valid @RequestBody BankTransferRequest req) {
        String paymentId = idGen.generate(MasonXIdPrefix.RAIL_PAYMENT.prefix());
        String network   = resolveBank(req.network());
        log.info("Rail bank-transfer request paymentId={} merchant={} network={}",
                paymentId, req.merchantId(), network);

        var command = new CanonicalPaymentCommand(
                paymentId,
                req.merchantId(),
                "TEST",
                req.idempotencyKey(),
                com.masonx.contracts.rail.PaymentRail.BANK_ISO20022,
                com.masonx.contracts.rail.MoneyMovementType.BANK_CREDIT_TRANSFER,
                req.amount(),
                req.currency(),
                null,
                new BankAccountRef(req.debtorIban(), null, req.debtorName()),
                new BankAccountRef(req.creditorIban(), null, req.creditorName()),
                null,
                Map.of("network", network)
        );

        RailResponse response = paymentService.authorize(command);
        return ResponseEntity.ok(new BankTransferResponse(
                response.railPaymentId(),
                response.status(),
                null,
                response.networkRef(),
                response.responseCode(),
                response.failureReason()
        ));
    }

    private String resolveBank(String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit.toUpperCase();
        return "SEPA_SIM";
    }

    private String resolveNetwork(String explicit, String pan) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.toUpperCase();
        }
        if (pan == null || pan.isBlank()) return "VISA_SIM";
        // 5-prefix → MC_SIM; 4-prefix and BIN 999999 → VISA_SIM
        // The simulator routes BIN 999999 to the VA issuer internally.
        if (pan.startsWith("5")) return "MC_SIM";
        return "VISA_SIM";
    }
}
