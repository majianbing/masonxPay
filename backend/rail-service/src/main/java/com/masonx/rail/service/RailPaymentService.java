package com.masonx.rail.service;

import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.RailPaymentStatus;
import com.masonx.rail.canonical.RailResponse;
import com.masonx.rail.router.RailRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Orchestrates the full lifecycle of a rail payment:
 * create DB record → route to adapter → update status → schedule reversal if UNKNOWN.
 *
 * <h3>MR2 — UNKNOWN state discipline</h3>
 * If the adapter returns {@code UNKNOWN} (TCP timeout), this service:
 * <ol>
 *   <li>Updates the DB record to {@code UNKNOWN} — NOT FAILED.
 *   <li>Creates a {@link ReversalTask} immediately via {@link ReversalTaskService}.
 * </ol>
 * A card reversal is a financial message (0400) sent to the network — it is NOT
 * a database rollback. The payment status moves to REVERSED only after a confirmed 0410.
 *
 * <h3>MR4 — settlement events</h3>
 * On APPROVED for a VA-issued prepaid card (BIN 999999), publishes a {@code CARD_SALE}
 * {@link RailSettlementEvent} so virtual-account-service can post the ledger journal.
 * Non-prepaid simulator cards have no VA ledger account and are skipped.
 */
@Service
public class RailPaymentService {

    private static final Logger log = LoggerFactory.getLogger(RailPaymentService.class);
    private static final String PREPAID_CARD_BIN = "999999";

    private final RailPaymentRepository       paymentRepo;
    private final RailRouter                  router;
    private final ReversalTaskService         reversalTaskService;
    private final RailSettlementEventPublisher publisher;

    public RailPaymentService(RailPaymentRepository paymentRepo,
                              RailRouter router,
                              ReversalTaskService reversalTaskService,
                              RailSettlementEventPublisher publisher) {
        this.paymentRepo         = paymentRepo;
        this.router              = router;
        this.reversalTaskService = reversalTaskService;
        this.publisher           = publisher;
    }

    public RailResponse authorize(CanonicalPaymentCommand command) {
        paymentRepo.insert(command, "SUBMITTED_TO_RAIL");
        log.info("Rail payment created paymentId={} rail={} network={}",
                command.paymentId(), command.rail(),
                command.metadata().getOrDefault("network", "?"));

        RailResponse response;
        try {
            response = router.route(command);
        } catch (Exception e) {
            paymentRepo.updateStatus(command.paymentId(), command.merchantId(), "FAILED");
            log.error("Rail payment failed paymentId={}: {}", command.paymentId(), e.getMessage(), e);
            throw e;
        }

        String dbStatus = toDbStatus(response.status());
        paymentRepo.updateStatus(command.paymentId(), command.merchantId(), dbStatus);
        log.info("Rail payment settled paymentId={} status={} responseCode={}",
                command.paymentId(), dbStatus, response.responseCode());

        if (response.status() == RailPaymentStatus.UNKNOWN) {
            scheduleReversal(command, response);
        }

        if (response.status() == RailPaymentStatus.APPROVED && isPrepaidCard(command)) {
            String network = command.metadata().getOrDefault("network", "UNKNOWN");
            publisher.publishCardSale(
                    command.paymentId(), command.merchantId(), network,
                    command.cardToken().masked(), command.amount(), command.currency());
        }

        return response;
    }

    private static boolean isPrepaidCard(CanonicalPaymentCommand command) {
        return command.cardToken() != null && PREPAID_CARD_BIN.equals(command.cardToken().bin());
    }

    /**
     * Parses STAN and RRN from the composite correlation key and creates a reversal task.
     *
     * <p>Correlation key format: {@code {network}:{acquirerId}:{stan}:{rrn}:{date}}.
     * If parsing fails, fallback values are used so the task is still created (it may
     * fail on its first attempt, which is logged, and then retry via backoff).
     */
    private void scheduleReversal(CanonicalPaymentCommand command, RailResponse response) {
        String network = command.metadata().getOrDefault("network", "VISA_SIM");
        String stan    = "000000";
        String rrn     = "000000000000";

        String ck = response.networkRef();
        if (ck != null) {
            String[] parts = ck.split(":");
            if (parts.length > 0) network = parts[0];
            if (parts.length > 2) stan    = parts[2];
            if (parts.length > 3) rrn     = parts[3];
        }

        reversalTaskService.createTask(command.paymentId(), network, stan, rrn, Instant.now());
    }

    private static String toDbStatus(RailPaymentStatus status) {
        return switch (status) {
            case APPROVED          -> "APPROVED";
            case DECLINED          -> "DECLINED";
            case UNKNOWN           -> "UNKNOWN";
            case REVERSAL_REQUIRED -> "REVERSAL_REQUIRED";
            case REVERSED          -> "REVERSED";
            case SETTLED           -> "SETTLED";
            case RETURNED          -> "RETURNED";
            default                -> "FAILED";
        };
    }
}
