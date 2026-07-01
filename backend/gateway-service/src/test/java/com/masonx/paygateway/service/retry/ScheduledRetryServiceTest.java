package com.masonx.paygateway.service.retry;

import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.retry.ScheduledRetryJobRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;
import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledRetryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-26T10:00:00Z");

    private ScheduledRetryJobRepository repository;
    private ScheduledRetryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledRetryJobRepository.class);
        service = new ScheduledRetryService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scheduleCreatesMerchantScopedCaptureRetry() {
        UUID merchantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(repository.save(any(ScheduledRetryJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.schedule(new ScheduledRetryRequest(
                merchantId,
                ScheduledRetryOperation.PAYMENT_CAPTURE,
                intentId,
                null,
                accountId,
                2,
                NOW.plusSeconds(300),
                "manual capture provider timeout",
                "timeout",
                "provider timed out",
                null));

        ArgumentCaptor<ScheduledRetryJob> captor = ArgumentCaptor.forClass(ScheduledRetryJob.class);
        verify(repository).save(captor.capture());
        ScheduledRetryJob saved = captor.getValue();
        assertThat(saved.getMerchantId()).isEqualTo(merchantId);
        assertThat(saved.getOperation()).isEqualTo(ScheduledRetryOperation.PAYMENT_CAPTURE);
        assertThat(saved.getStatus()).isEqualTo(ScheduledRetryStatus.SCHEDULED);
        assertThat(saved.getPaymentIntentId()).isEqualTo(intentId);
        assertThat(saved.getConnectorAccountId()).isEqualTo(accountId);
        assertThat(saved.getAttemptCount()).isZero();
        assertThat(saved.getMaxAttempts()).isEqualTo(2);
        assertThat(saved.getNextRunAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(saved.getLastErrorCode()).isEqualTo("timeout");
    }

    @Test
    void scheduleRejectsRefundRetryWithoutRefundId() {
        assertThatThrownBy(() -> service.schedule(new ScheduledRetryRequest(
                UUID.randomUUID(),
                ScheduledRetryOperation.REFUND,
                UUID.randomUUID(),
                null,
                null,
                3,
                NOW,
                null,
                null,
                null,
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refundId is required");
    }

    @Test
    void cancelOnlyAllowsScheduledJobs() {
        UUID merchantId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ScheduledRetryJob job = new ScheduledRetryJob();
        job.setMerchantId(merchantId);
        job.setStatus(ScheduledRetryStatus.SCHEDULED);
        when(repository.findByIdAndMerchantId(jobId, merchantId)).thenReturn(Optional.of(job));
        when(repository.save(job)).thenReturn(job);

        ScheduledRetryJob canceled = service.cancel(merchantId, jobId.toString());

        assertThat(canceled.getStatus()).isEqualTo(ScheduledRetryStatus.CANCELED);
        assertThat(canceled.getCompletedAt()).isEqualTo(NOW);
        verify(repository).save(job);
    }

    @Test
    void cancelRejectsProcessingJobs() {
        UUID merchantId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ScheduledRetryJob job = new ScheduledRetryJob();
        job.setMerchantId(merchantId);
        job.setStatus(ScheduledRetryStatus.PROCESSING);
        when(repository.findByIdAndMerchantId(jobId, merchantId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancel(merchantId, jobId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only scheduled retry jobs can be canceled");
    }

    @Test
    void dueJobsUsesCurrentClockAndLimit() {
        ScheduledRetryJob job = new ScheduledRetryJob();
        when(repository.findDue(eq(NOW), any(Pageable.class))).thenReturn(List.of(job));

        List<ScheduledRetryJob> jobs = service.dueJobs(25);

        assertThat(jobs).containsExactly(job);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findDue(eq(NOW), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
    }
}
