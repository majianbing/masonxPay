package com.masonx.paygateway.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.feeengine.DefaultFeeEngine;
import com.masonx.feeengine.FeeComponent;
import com.masonx.feeengine.FeeRule;
import com.masonx.feeengine.FeeVisibility;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayFeeAssessmentServiceTest {

    private GatewayFeeScheduleRepository scheduleRepository;
    private GatewayFeeAssessmentRepository assessmentRepository;
    private GatewayFeeAssessmentService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        scheduleRepository = mock(GatewayFeeScheduleRepository.class);
        assessmentRepository = mock(GatewayFeeAssessmentRepository.class);
        objectMapper = new ObjectMapper();
        service = new GatewayFeeAssessmentService(
                scheduleRepository,
                assessmentRepository,
                new SnowflakeIdGenerator(0),
                DefaultFeeEngine.withAviator(),
                objectMapper);
    }

    @Test
    void assessAndPersist_withoutActiveSchedule_isNoop() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(assessmentRepository.findByEvent(merchantId, ApiKeyMode.TEST, "PAYMENT_CONFIRM", eventId))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveVersion(eq(merchantId), eq(ApiKeyMode.TEST), eq("PAYMENT_CONFIRM"),
                eq(PaymentProvider.SIMULATOR), any(), eq("card"), any()))
                .thenReturn(Optional.empty());

        Optional<GatewayFeeAssessmentSnapshot> result = service.assessAndPersist(command(merchantId, eventId));

        assertThat(result).isEmpty();
        verify(assessmentRepository, never()).saveSnapshotIfAbsent(any(), any());
    }

    @Test
    void assessAndPersist_withMatchingSchedule_persistsSnapshot() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        GatewayFeeScheduleVersion version = version(List.of(
                new FeeRule(
                        "gateway_authorization",
                        1,
                        "Gateway authorization",
                        100,
                        "eventType == 'PAYMENT_CONFIRM'",
                        List.of(
                                FeeComponent.percentage(
                                        "authorization_pct",
                                        "Authorization percentage",
                                        100,
                                        "amount",
                                        FeeVisibility.MERCHANT_VISIBLE),
                                FeeComponent.fixed(
                                        "authorization_fixed",
                                        "Authorization fixed",
                                        new BigDecimal("0.10"),
                                        "USD",
                                        FeeVisibility.MERCHANT_VISIBLE)),
                        FeeVisibility.MERCHANT_VISIBLE,
                        false,
                        Map.of())));
        when(assessmentRepository.findByEvent(merchantId, ApiKeyMode.TEST, "PAYMENT_CONFIRM", eventId))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveVersion(eq(merchantId), eq(ApiKeyMode.TEST), eq("PAYMENT_CONFIRM"),
                eq(PaymentProvider.SIMULATOR), any(), eq("card"), any()))
                .thenReturn(Optional.of(version));
        when(assessmentRepository.saveSnapshotIfAbsent(any(), any())).thenReturn(true);

        Optional<GatewayFeeAssessmentSnapshot> result = service.assessAndPersist(command(merchantId, eventId));

        assertThat(result).isPresent();
        ArgumentCaptor<GatewayFeeAssessment> assessmentCaptor = ArgumentCaptor.forClass(GatewayFeeAssessment.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GatewayFeeAssessmentLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(assessmentRepository).saveSnapshotIfAbsent(assessmentCaptor.capture(), linesCaptor.capture());
        GatewayFeeAssessment assessment = assessmentCaptor.getValue();
        assertThat(assessment.merchantId()).isEqualTo(merchantId);
        assertThat(assessment.mode()).isEqualTo(ApiKeyMode.TEST);
        assertThat(assessment.eventType()).isEqualTo("PAYMENT_CONFIRM");
        assertThat(assessment.eventId()).isEqualTo(eventId);
        assertThat(assessment.visibleTotalsJson()).contains("USD").contains("1.10");
        assertThat(linesCaptor.getValue()).extracting(GatewayFeeAssessmentLine::amount)
                .containsExactly(new BigDecimal("1.00"), new BigDecimal("0.10"));
    }

    @Test
    void assessAndPersist_rejectsUnsafeContextKeys() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AssessGatewayFeeCommand command = new AssessGatewayFeeCommand(
                merchantId,
                ApiKeyMode.TEST,
                "PAYMENT_CONFIRM",
                eventId,
                eventId,
                null,
                PaymentProvider.SIMULATOR,
                UUID.randomUUID(),
                "card",
                "USD",
                10_000L,
                Map.of("rawPayload", "{}"),
                Instant.now());

        when(assessmentRepository.findByEvent(merchantId, ApiKeyMode.TEST, "PAYMENT_CONFIRM", eventId))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveVersion(eq(merchantId), eq(ApiKeyMode.TEST), eq("PAYMENT_CONFIRM"),
                eq(PaymentProvider.SIMULATOR), any(), eq("card"), any()))
                .thenReturn(Optional.of(version(List.of())));

        assertThatThrownBy(() -> service.assessAndPersist(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe fee context key");
        verify(assessmentRepository, never()).saveSnapshotIfAbsent(any(), any());
    }

    private AssessGatewayFeeCommand command(UUID merchantId, UUID eventId) {
        return new AssessGatewayFeeCommand(
                merchantId,
                ApiKeyMode.TEST,
                "PAYMENT_CONFIRM",
                eventId,
                eventId,
                UUID.randomUUID(),
                PaymentProvider.SIMULATOR,
                UUID.randomUUID(),
                "card",
                "USD",
                10_000L,
                Map.of(),
                Instant.now());
    }

    private GatewayFeeScheduleVersion version(List<FeeRule> rules) {
        try {
            return new GatewayFeeScheduleVersion(
                    "fees_test",
                    UUID.randomUUID(),
                    ApiKeyMode.TEST,
                    1,
                    GatewayFeeScheduleStatus.ACTIVE,
                    "USD",
                    2,
                    RoundingMode.HALF_UP.name(),
                    Instant.now().minusSeconds(60),
                    null,
                    objectMapper.writeValueAsString(rules),
                    "{}",
                    Instant.now(),
                    Instant.now());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
