package com.masonx.rail.service;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583ReversalSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Manages the lifecycle of reversal tasks created when a card auth times out.
 *
 * <h3>State machine</h3>
 * <pre>
 *   PENDING ──(scheduler claims)──► SENT ──(0410 approved)──► RESOLVED
 *                                    │
 *                                    └──(0410 timeout/error, attempts < max)──► PENDING (next_attempt_at set)
 *                                    │
 *                                    └──(attempts == max)──► EXHAUSTED  (requires manual reconciliation)
 * </pre>
 *
 * <h3>Key invariant</h3>
 * A card reversal is a financial message (0400) sent to the network. It is NOT a
 * database rollback. UNKNOWN → REVERSED only after a confirmed 0410 DE39=00.
 */
@Service
public class ReversalTaskService {

    private static final Logger log = LoggerFactory.getLogger(ReversalTaskService.class);

    // Backoff delays (seconds) indexed by 0-based attempt number.
    private static final long[] BACKOFF_SECONDS = { 30L, 120L, 300L };

    private final ReversalTaskRepository         reversalRepo;
    private final RailPaymentRepository          paymentRepo;
    private final Iso8583ReversalSender          reversalSender;
    private final Iso8583LogService              logService;
    private final SnowflakeIdGenerator           idGen;
    private final RailSettlementEventPublisher   publisher;
    private final RailPaymentResolvedPublisher   resolvedPublisher;

    public ReversalTaskService(ReversalTaskRepository reversalRepo,
                               RailPaymentRepository paymentRepo,
                               Iso8583ReversalSender reversalSender,
                               Iso8583LogService logService,
                               SnowflakeIdGenerator idGen,
                               RailSettlementEventPublisher publisher,
                               RailPaymentResolvedPublisher resolvedPublisher) {
        this.reversalRepo      = reversalRepo;
        this.paymentRepo       = paymentRepo;
        this.reversalSender    = reversalSender;
        this.logService        = logService;
        this.idGen             = idGen;
        this.publisher         = publisher;
        this.resolvedPublisher = resolvedPublisher;
    }

    /**
     * Creates a reversal task immediately after a card auth times out (UNKNOWN state).
     *
     * <p>Must be called as soon as the adapter returns UNKNOWN — the task scheduler
     * picks it up on the next tick (within 5 seconds).
     */
    public void createTask(String paymentId, String network,
                           String originalStan, String originalRrn, Instant originalTxTime) {
        String taskId = idGen.generate(MasonXIdPrefix.REVERSAL_TASK.prefix());
        reversalRepo.insert(taskId, paymentId, network, originalStan, originalRrn, originalTxTime);
        log.info("Reversal task created taskId={} paymentId={} originalStan={}",
                taskId, paymentId, originalStan);
    }

    /**
     * Background worker: claims PENDING due tasks, sends 0400, updates status.
     *
     * <p>Runs every 5 seconds with a fixed delay (not fixed rate) so tasks from one
     * tick cannot pile up into the next if processing takes longer than 5 s.
     */
    @Scheduled(fixedDelay = 5000)
    public void executeReversals() {
        List<ReversalTask> tasks;
        try {
            tasks = reversalRepo.findAndClaimDueTasks();
        } catch (Exception e) {
            log.error("Failed to claim reversal tasks: {}", e.getMessage(), e);
            return;
        }

        for (ReversalTask task : tasks) {
            try {
                processOneReversal(task);
            } catch (Exception e) {
                log.error("Unexpected error processing reversal taskId={} paymentId={}: {}",
                        task.id(), task.paymentId(), e.getMessage(), e);
                handleReversalFailure(task);
            }
        }
    }

    private void processOneReversal(ReversalTask task) {
        boolean reversed = reversalSender.sendReversal(task);

        if (reversed) {
            paymentRepo.updateStatus(task.paymentId(), task.merchantId(), "REVERSED");
            reversalRepo.markResolved(task.id());
            log.info("Reversal RESOLVED taskId={} paymentId={}", task.id(), task.paymentId());
            publishCardReversalIfPrepaid(task);
            // Notify gateway-service so it can finalize the PROCESSING PaymentIntent as FAILED.
            resolvedPublisher.publish(task.paymentId(), task.merchantId(), "FAILED");
        } else {
            handleReversalFailure(task);
        }
    }

    private void publishCardReversalIfPrepaid(ReversalTask task) {
        if (task.cardTokenId() != null) {
            publisher.publishCardReversal(
                    task.paymentId(), task.merchantId(), task.network(),
                    task.cardTokenId(), task.maskedPan(), task.amount(), task.currency());
        }
    }

    /**
     * Requeues the task with exponential backoff, or exhausts it after max_attempts.
     *
     * <p>{@code task.attempts()} is the count BEFORE the current attempt was claimed
     * (the repository increments it in the same transaction as the SENT update).
     * So {@code attemptsSoFar = task.attempts() + 1}.
     */
    private void handleReversalFailure(ReversalTask task) {
        int attemptsSoFar = task.attempts() + 1; // attempts() was pre-increment snapshot

        if (attemptsSoFar >= task.maxAttempts()) {
            reversalRepo.exhaustTask(task.id());
            paymentRepo.updateStatus(task.paymentId(), task.merchantId(), "REVERSAL_REQUIRED");
            log.error("Reversal EXHAUSTED after {} attempt(s) — manual intervention required " +
                            "taskId={} paymentId={}",
                    attemptsSoFar, task.id(), task.paymentId());
            logService.logReconException(task.paymentId(), task.network(), "REVERSAL_EXHAUSTED");
        } else {
            long backoffSec = BACKOFF_SECONDS[Math.min(attemptsSoFar - 1, BACKOFF_SECONDS.length - 1)];
            reversalRepo.requeueTask(task.id(), backoffSec);
            log.warn("Reversal attempt {} failed, retrying in {}s taskId={} paymentId={}",
                    attemptsSoFar, backoffSec, task.id(), task.paymentId());
        }
    }
}
