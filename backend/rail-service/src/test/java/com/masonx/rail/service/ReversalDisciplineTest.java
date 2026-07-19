package com.masonx.rail.service;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583ReversalSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for reversal task lifecycle: create → claim → send 0400 → resolve or retry.
 *
 * <p>Verifies the core MR2 invariant: timeout → UNKNOWN, reversal task created, 0400 sent,
 * REVERSED only after confirmed 0410. No database state is mutated (all repos are mocked).
 */
class ReversalDisciplineTest {

    @Mock ReversalTaskRepository         reversalRepo;
    @Mock RailPaymentRepository          paymentRepo;
    @Mock Iso8583ReversalSender          reversalSender;
    @Mock Iso8583LogService              logService;
    @Mock RailSettlementEventPublisher   publisher;
    @Mock RailPaymentResolvedPublisher   resolvedPublisher;

    private ReversalTaskService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ReversalTaskService(reversalRepo, paymentRepo, reversalSender, logService,
                new SnowflakeIdGenerator(0), publisher, resolvedPublisher);
    }

    // ── task creation ─────────────────────────────────────────────────────────

    @Test
    void createTask_insertsRecord_withCorrectFields() {
        service.createTask("pmnt_1", "VISA_SIM", "000042", "012345678901", Instant.now());

        ArgumentCaptor<String> idCap   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> netwCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stanCap = ArgumentCaptor.forClass(String.class);

        verify(reversalRepo).insert(idCap.capture(), eq("pmnt_1"), netwCap.capture(),
                stanCap.capture(), eq("012345678901"), any(Instant.class));

        assertThat(idCap.getValue()).startsWith("rtask_");
        assertThat(netwCap.getValue()).isEqualTo("VISA_SIM");
        assertThat(stanCap.getValue()).isEqualTo("000042");
    }

    // ── happy path: UNKNOWN → 0400 sent → 0410 approved → REVERSED ───────────

    @Test
    void executeReversals_onSuccess_marksResolvedAndReversed() {
        ReversalTask task = makeTask("t1", "pmnt_1", 0, 3);
        when(reversalRepo.findAndClaimDueTasks()).thenReturn(List.of(task));
        when(reversalSender.sendReversal(task)).thenReturn(true);

        service.executeReversals();

        verify(paymentRepo).updateStatus("pmnt_1", "merch_1", "REVERSED");
        verify(reversalRepo).markResolved("t1");
        // Gateway-service must be notified so it can finalize the PROCESSING PaymentIntent.
        verify(resolvedPublisher).publish("pmnt_1", "merch_1", "FAILED");
        verifyNoMoreInteractions(logService);
    }

    // ── retry path: 0410 timeout → requeue with backoff ──────────────────────

    @Test
    void executeReversals_onFirstFailure_requeuesWithBackoff() {
        ReversalTask task = makeTask("t1", "pmnt_1", 0, 3);
        when(reversalRepo.findAndClaimDueTasks()).thenReturn(List.of(task));
        when(reversalSender.sendReversal(task)).thenReturn(false);

        service.executeReversals();

        // Attempt 1 failed (attemptsSoFar=1 < maxAttempts=3) → requeue, not exhaust.
        verify(reversalRepo).requeueTask(eq("t1"), eq(30L));
        verify(reversalRepo, never()).exhaustTask(any());
        verify(paymentRepo, never()).updateStatus(any(), any(), eq("REVERSED"));
        verify(paymentRepo, never()).updateStatus(any(), any(), eq("REVERSAL_REQUIRED"));
        verify(resolvedPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void executeReversals_onSecondFailure_requeuesWithLongerBackoff() {
        ReversalTask task = makeTask("t1", "pmnt_1", 1, 3); // attempts=1 → this is attempt 2
        when(reversalRepo.findAndClaimDueTasks()).thenReturn(List.of(task));
        when(reversalSender.sendReversal(task)).thenReturn(false);

        service.executeReversals();

        verify(reversalRepo).requeueTask(eq("t1"), eq(120L));
        verify(reversalRepo, never()).exhaustTask(any());
    }

    // ── exhaustion path: max attempts reached → REVERSAL_REQUIRED ────────────

    @Test
    void executeReversals_onThirdFailure_exhaustsTaskAndUpdatesPayment() {
        ReversalTask task = makeTask("t1", "pmnt_1", 2, 3); // attempts=2 → this is attempt 3
        when(reversalRepo.findAndClaimDueTasks()).thenReturn(List.of(task));
        when(reversalSender.sendReversal(task)).thenReturn(false);

        service.executeReversals();

        verify(reversalRepo).exhaustTask("t1");
        verify(paymentRepo).updateStatus("pmnt_1", "merch_1", "REVERSAL_REQUIRED");
        verify(logService).logReconException("pmnt_1", "VISA_SIM", "REVERSAL_EXHAUSTED");
        verify(reversalRepo, never()).requeueTask(any(), anyLong());
        verify(resolvedPublisher, never()).publish(any(), any(), any());
    }

    // ── scheduler resilience ──────────────────────────────────────────────────

    @Test
    void executeReversals_withNoDueTasks_doesNothing() {
        when(reversalRepo.findAndClaimDueTasks()).thenReturn(Collections.emptyList());

        service.executeReversals();

        verifyNoInteractions(reversalSender, paymentRepo, logService);
    }

    @Test
    void executeReversals_whenReversalSenderThrows_handlesFailureGracefully() {
        ReversalTask task = makeTask("t1", "pmnt_1", 0, 3);
        when(reversalRepo.findAndClaimDueTasks()).thenReturn(List.of(task));
        when(reversalSender.sendReversal(task)).thenThrow(new RuntimeException("Netty error"));

        // Must not propagate — scheduler must not die.
        service.executeReversals();

        verify(reversalRepo).requeueTask(eq("t1"), anyLong());
    }

    @Test
    void executeReversals_whenRepoThrows_doesNotPropagateException() {
        when(reversalRepo.findAndClaimDueTasks()).thenThrow(new RuntimeException("DB down"));

        // Scheduler must survive DB errors.
        service.executeReversals();

        verifyNoInteractions(reversalSender, paymentRepo, logService);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReversalTask makeTask(String id, String paymentId, int attempts, int maxAttempts) {
        return new ReversalTask(
                id, paymentId, attempts, maxAttempts,
                "000042", "012345678901", "VISA_SIM",
                Instant.now(), new BigDecimal("19.99"), "USD", "merch_1",
                null, null);   // cardTokenId/maskedPan — null for non-prepaid test tasks
    }
}
