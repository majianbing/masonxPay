package com.masonx.virtualaccount.fee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.feeengine.DefaultFeeEngine;
import com.masonx.feeengine.FeeComponent;
import com.masonx.feeengine.FeeRule;
import com.masonx.feeengine.FeeVisibility;
import com.masonx.virtualaccount.domain.PrepaidFeeAssessmentRepository;
import com.masonx.virtualaccount.domain.PrepaidFeeScheduleRepository;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessment;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentLine;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import com.masonx.virtualaccount.domain.po.PrepaidFeeScheduleVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrepaidFeeAssessmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Mock
    private PrepaidFeeScheduleRepository scheduleRepository;
    @Mock
    private PrepaidFeeAssessmentRepository assessmentRepository;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private ObjectMapper objectMapper;
    private PrepaidFeeAssessmentService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new PrepaidFeeAssessmentService(
                scheduleRepository,
                assessmentRepository,
                idGenerator,
                DefaultFeeEngine.withAviator(),
                objectMapper);
    }

    @Test
    void assessAndPersist_computesAndStoresCreateCardFeeSnapshot() {
        when(assessmentRepository.findByEvent("mer_1", Mode.TEST, "CARD_CREATE", "evt_1"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveVersion("mer_1", Mode.TEST, "cprog_1", "411111", "VIRTUAL", NOW))
                .thenReturn(Optional.of(scheduleVersion(List.of(createCardRule()))));
        when(idGenerator.generate(MasonXIdPrefix.FEE_ASSESSMENT.prefix())).thenReturn("feeas_1");
        when(assessmentRepository.saveSnapshotIfAbsent(any(), any())).thenReturn(true);

        Optional<PrepaidFeeAssessmentSnapshot> result = service.assessAndPersist(new AssessPrepaidFeeCommand(
                "mer_1",
                Mode.TEST,
                "CARD_CREATE",
                "evt_1",
                "cprog_1",
                "vc_1",
                "411111",
                "VIRTUAL",
                Map.of("cardCurrency", "USD"),
                NOW));

        assertThat(result).isPresent();
        ArgumentCaptor<PrepaidFeeAssessment> assessmentCaptor = ArgumentCaptor.forClass(PrepaidFeeAssessment.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrepaidFeeAssessmentLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(assessmentRepository).saveSnapshotIfAbsent(assessmentCaptor.capture(), linesCaptor.capture());

        PrepaidFeeAssessment assessment = assessmentCaptor.getValue();
        assertThat(assessment.assessmentId()).isEqualTo("feeas_1");
        assertThat(assessment.scheduleId()).isEqualTo("fees_1");
        assertThat(assessment.scheduleVersion()).isEqualTo(1);
        assertThat(assessment.contextJson()).contains("\"eventType\":\"CARD_CREATE\"");
        assertThat(assessment.contextJson()).contains("\"cardCurrency\":\"USD\"");
        assertThat(assessment.visibleTotalsJson()).contains("\"USD\":1.00");
        assertThat(assessment.hiddenTotalsJson()).isEqualTo("{}");

        assertThat(linesCaptor.getValue()).hasSize(1);
        PrepaidFeeAssessmentLine line = linesCaptor.getValue().get(0);
        assertThat(line.assessmentId()).isEqualTo("feeas_1");
        assertThat(line.ruleId()).isEqualTo("rule_create_card");
        assertThat(line.amount()).isEqualByComparingTo("1.00");
        assertThat(line.roundingMode()).isEqualTo("HALF_UP");
        assertThat(line.roundingScale()).isEqualTo(2);
    }

    @Test
    void assessAndPersist_returnsExistingSnapshotWithoutRecomputing() {
        PrepaidFeeAssessmentSnapshot existing = new PrepaidFeeAssessmentSnapshot(
                new PrepaidFeeAssessment(
                        "feeas_existing",
                        "mer_1",
                        Mode.TEST,
                        "CARD_CREATE",
                        "evt_1",
                        "cprog_1",
                        "vc_1",
                        "fees_old",
                        1,
                        "{}",
                        "[]",
                        "{}",
                        "{}",
                        NOW),
                List.of());
        when(assessmentRepository.findByEvent("mer_1", Mode.TEST, "CARD_CREATE", "evt_1"))
                .thenReturn(Optional.of(existing));

        Optional<PrepaidFeeAssessmentSnapshot> result = service.assessAndPersist(new AssessPrepaidFeeCommand(
                "mer_1", Mode.TEST, "CARD_CREATE", "evt_1", "cprog_1", "vc_1",
                "411111", "VIRTUAL", Map.of(), NOW));

        assertThat(result).containsSame(existing);
        verifyNoInteractions(scheduleRepository);
        verify(idGenerator, never()).generate(any());
        verify(assessmentRepository, never()).saveSnapshotIfAbsent(any(), any());
    }

    @Test
    void assessAndPersist_returnsEmptyWhenNoActiveScheduleExists() {
        when(assessmentRepository.findByEvent("mer_1", Mode.TEST, "CARD_CLEARING", "evt_1"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveVersion("mer_1", Mode.TEST, "cprog_1", null, null, NOW))
                .thenReturn(Optional.empty());

        Optional<PrepaidFeeAssessmentSnapshot> result = service.assessAndPersist(new AssessPrepaidFeeCommand(
                "mer_1", Mode.TEST, "CARD_CLEARING", "evt_1", "cprog_1", "vc_1",
                null, null, Map.of("amount", new BigDecimal("10.00")), NOW));

        assertThat(result).isEmpty();
        verify(assessmentRepository, never()).saveSnapshotIfAbsent(any(), any());
    }

    @Test
    void assessAndPersist_rejectsUnsafeContextKeys() {
        when(assessmentRepository.findByEvent("mer_1", Mode.TEST, "CARD_CREATE", "evt_1"))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveVersion(eq("mer_1"), eq(Mode.TEST), any(), any(), any(), eq(NOW)))
                .thenReturn(Optional.of(scheduleVersion(List.of(createCardRule()))));

        assertThatThrownBy(() -> service.assessAndPersist(new AssessPrepaidFeeCommand(
                "mer_1", Mode.TEST, "CARD_CREATE", "evt_1", "cprog_1", "vc_1",
                "411111", "VIRTUAL", Map.of("pan", "4111111111111111"), NOW)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsafe fee context key");
    }

    private PrepaidFeeScheduleVersion scheduleVersion(List<FeeRule> rules) {
        return new PrepaidFeeScheduleVersion(
                "fees_1",
                "mer_1",
                Mode.TEST,
                1,
                PrepaidFeeScheduleStatus.ACTIVE,
                "USD",
                2,
                "HALF_UP",
                NOW.minusSeconds(60),
                null,
                toJson(rules),
                "{}",
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
    }

    private static FeeRule createCardRule() {
        return new FeeRule(
                "rule_create_card",
                1,
                "create_card_fee",
                10,
                "eventType == 'CARD_CREATE'",
                List.of(FeeComponent.fixed(
                        "fixed_create_card",
                        "create_card_fixed",
                        new BigDecimal("1.00"),
                        "USD",
                        FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                false,
                Map.of());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
