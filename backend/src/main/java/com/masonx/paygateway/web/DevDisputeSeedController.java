package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.dispute.*;
import com.masonx.paygateway.web.dto.DisputeResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Dev-only endpoint for seeding test disputes.
 * Only loaded when the "dev" Spring profile is active — never present in production.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/dev/disputes")
@Profile("!preview")
public class DevDisputeSeedController {

    private static final List<DisputeStatus> SEEDABLE_STATUSES = List.of(
            DisputeStatus.NEEDS_RESPONSE,
            DisputeStatus.UNDER_REVIEW,
            DisputeStatus.WON,
            DisputeStatus.LOST,
            DisputeStatus.WARNING_NEEDS_RESPONSE
    );

    private static final List<DisputeReason> REASONS = List.of(
            DisputeReason.FRAUDULENT,
            DisputeReason.PRODUCT_NOT_RECEIVED,
            DisputeReason.PRODUCT_UNACCEPTABLE,
            DisputeReason.DUPLICATE,
            DisputeReason.SUBSCRIPTION_CANCELED,
            DisputeReason.GENERAL
    );

    private static final List<String> PROVIDERS = List.of("STRIPE", "SQUARE", "BRAINTREE");

    private final DisputeRepository disputeRepository;

    public DevDisputeSeedController(DisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @PostMapping("/seed")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CHARGEBACK', 'READ')")
    public ResponseEntity<DisputeResponse> seed(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "NEEDS_RESPONSE") DisputeStatus status,
            @RequestParam(defaultValue = "STRIPE") String provider) {

        Random rng = new Random();

        // Pick random values when not specified
        DisputeStatus resolvedStatus = SEEDABLE_STATUSES.contains(status) ? status : DisputeStatus.NEEDS_RESPONSE;
        String resolvedProvider = PROVIDERS.contains(provider.toUpperCase())
                ? provider.toUpperCase() : "STRIPE";
        DisputeReason reason = REASONS.get(rng.nextInt(REASONS.size()));

        long amountCents = (long) (rng.nextInt(49) + 1) * 100 + rng.nextInt(100); // $1–$50

        Dispute dispute = new Dispute();
        dispute.setMerchantId(merchantId);
        dispute.setProvider(resolvedProvider);
        dispute.setProviderDisputeId("dp_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        dispute.setStatus(resolvedStatus);
        dispute.setReason(reason);
        dispute.setAmount(amountCents);
        dispute.setCurrency("USD");
        dispute.setMode(ApiKeyMode.TEST);

        if (resolvedStatus == DisputeStatus.NEEDS_RESPONSE
                || resolvedStatus == DisputeStatus.WARNING_NEEDS_RESPONSE) {
            // Random deadline 3–14 days out
            int daysOut = 3 + rng.nextInt(12);
            dispute.setEvidenceDueBy(Instant.now().plus(daysOut, ChronoUnit.DAYS));
        }

        if (resolvedStatus == DisputeStatus.WON || resolvedStatus == DisputeStatus.LOST) {
            dispute.setResolvedAt(Instant.now().minus(rng.nextInt(5) + 1, ChronoUnit.DAYS));
        }

        disputeRepository.save(dispute);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DisputeResponse.from(dispute, List.of()));
    }
}
