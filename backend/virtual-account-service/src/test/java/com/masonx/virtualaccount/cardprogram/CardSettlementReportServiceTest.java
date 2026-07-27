package com.masonx.virtualaccount.cardprogram;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.virtualaccount.cardprogram.dto.IngestCardSettlementReportRequest;
import com.masonx.virtualaccount.domain.CardClearingEventRepository;
import com.masonx.virtualaccount.domain.CardProgramRepository;
import com.masonx.virtualaccount.domain.CardSettlementReportRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.po.CardClearingEvent;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.CardSettlementReport;
import com.masonx.virtualaccount.domain.po.CardSettlementReportLine;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardSettlementReportServiceTest {

    @Mock CardProgramRepository cardPrograms;
    @Mock CardClearingEventRepository clearingEvents;
    @Mock CardSettlementReportRepository reports;
    @Mock VirtualCardRepository virtualCards;

    CardSettlementReportService service;

    @BeforeEach
    void setUp() {
        service = new CardSettlementReportService(
                cardPrograms, clearingEvents, reports, virtualCards, new SnowflakeIdGenerator(0));
    }

    @Test
    void ingest_matched_line_records_matched_report() {
        when(cardPrograms.findByIdForMerchant("cprog_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(program()));
        when(reports.findByReportRef("mer_1", Mode.TEST, "ip_1", "report_2026_07_26"))
                .thenReturn(Optional.empty());
        when(clearingEvents.findByRailPaymentId("pay_1"))
                .thenReturn(Optional.of(clearing("pay_1", "100.00", "USD",
                        MoneyMovementType.CARD_CLEARING_PRESENTMENT.name())));
        when(virtualCards.findById("card_1")).thenReturn(Optional.of(card("cprog_1")));

        var response = service.ingest(request(line("pay_1", "100.00", "USD",
                MoneyMovementType.CARD_CLEARING_PRESENTMENT.name())));

        assertThat(response.status()).isEqualTo(CardSettlementReportStatus.MATCHED);
        assertThat(response.matchedAmount()).isEqualByComparingTo("100.00");
        assertThat(response.exceptionCount()).isZero();
        assertThat(response.lines()).singleElement()
                .satisfies(line -> assertThat(line.status()).isEqualTo(CardSettlementReportLineStatus.MATCHED));

        ArgumentCaptor<CardSettlementReport> reportCaptor = ArgumentCaptor.forClass(CardSettlementReport.class);
        verify(reports).insertReport(reportCaptor.capture());
        assertThat(reportCaptor.getValue().status()).isEqualTo(CardSettlementReportStatus.MATCHED);
        verify(reports).insertLine(any(CardSettlementReportLine.class));
    }

    @Test
    void ingest_missing_clearing_records_exception_line() {
        when(cardPrograms.findByIdForMerchant("cprog_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(program()));
        when(reports.findByReportRef("mer_1", Mode.TEST, "ip_1", "report_2026_07_26"))
                .thenReturn(Optional.empty());
        when(clearingEvents.findByRailPaymentId("pay_missing")).thenReturn(Optional.empty());

        var response = service.ingest(request(line("pay_missing", "100.00", "USD",
                MoneyMovementType.CARD_CLEARING_PRESENTMENT.name())));

        assertThat(response.status()).isEqualTo(CardSettlementReportStatus.EXCEPTION);
        assertThat(response.exceptionAmount()).isEqualByComparingTo("100.00");
        assertThat(response.lines()).singleElement()
                .satisfies(line -> assertThat(line.status())
                        .isEqualTo(CardSettlementReportLineStatus.CLEARING_NOT_FOUND));
        verifyNoInteractions(virtualCards);
    }

    @Test
    void ingest_amount_mismatch_records_difference() {
        when(cardPrograms.findByIdForMerchant("cprog_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(program()));
        when(reports.findByReportRef("mer_1", Mode.TEST, "ip_1", "report_2026_07_26"))
                .thenReturn(Optional.empty());
        when(clearingEvents.findByRailPaymentId("pay_1"))
                .thenReturn(Optional.of(clearing("pay_1", "90.00", "USD",
                        MoneyMovementType.CARD_CLEARING_PRESENTMENT.name())));
        when(virtualCards.findById("card_1")).thenReturn(Optional.of(card("cprog_1")));

        var response = service.ingest(request(line("pay_1", "100.00", "USD",
                MoneyMovementType.CARD_CLEARING_PRESENTMENT.name())));

        assertThat(response.status()).isEqualTo(CardSettlementReportStatus.EXCEPTION);
        assertThat(response.lines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.status()).isEqualTo(CardSettlementReportLineStatus.AMOUNT_MISMATCH);
                    assertThat(line.mismatchAmount()).isEqualByComparingTo("10.00");
                });
    }

    @Test
    void ingest_duplicate_report_ref_returns_existing_report_without_reinsert() {
        when(cardPrograms.findByIdForMerchant("cprog_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(program()));
        CardSettlementReport existing = new CardSettlementReport(
                "csr_existing", "mer_1", Mode.TEST, "cprog_1", "ip_1",
                "report_2026_07_26", LocalDate.of(2026, 7, 26), "USD",
                new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO,
                1, 0, CardSettlementReportStatus.MATCHED, Instant.now());
        when(reports.findByReportRef("mer_1", Mode.TEST, "ip_1", "report_2026_07_26"))
                .thenReturn(Optional.of(existing));
        when(reports.findLines("csr_existing", "mer_1", Mode.TEST)).thenReturn(List.of());

        var response = service.ingest(request(line("pay_1", "100.00", "USD",
                MoneyMovementType.CARD_CLEARING_PRESENTMENT.name())));

        assertThat(response.reportId()).isEqualTo("csr_existing");
        verify(reports, never()).insertReport(any());
        verify(reports, never()).insertLine(any());
        verifyNoInteractions(clearingEvents, virtualCards);
    }

    @Test
    void summarize_returns_exception_when_ledger_posted_amount_differs_from_issuer_total() {
        when(cardPrograms.findByIdForMerchant("cprog_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(program()));
        when(reports.summarizeProgramDate(
                "mer_1", Mode.TEST, "cprog_1", LocalDate.of(2026, 7, 26), "USD"))
                .thenReturn(new CardSettlementReportRepository.CardSettlementSummaryAmounts(
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        1,
                        0,
                        new BigDecimal("100.00"),
                        new BigDecimal("90.00")));

        var response = service.summarize(
                "mer_1", Mode.TEST, "cprog_1", LocalDate.of(2026, 7, 26), "usd");

        assertThat(response.status()).isEqualTo("EXCEPTION");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.clearingDelta()).isEqualByComparingTo("0.00");
        assertThat(response.ledgerDelta()).isEqualByComparingTo("10.00");
    }

    @Test
    void summarize_returns_matched_when_report_clearing_and_ledger_totals_align() {
        when(cardPrograms.findByIdForMerchant("cprog_1", "mer_1", Mode.TEST))
                .thenReturn(Optional.of(program()));
        when(reports.summarizeProgramDate(
                "mer_1", Mode.TEST, "cprog_1", LocalDate.of(2026, 7, 26), "USD"))
                .thenReturn(new CardSettlementReportRepository.CardSettlementSummaryAmounts(
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        1,
                        0,
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00")));

        var response = service.summarize(
                "mer_1", Mode.TEST, "cprog_1", LocalDate.of(2026, 7, 26), "USD");

        assertThat(response.status()).isEqualTo("MATCHED");
        assertThat(response.issuerReportAmount()).isEqualByComparingTo("100.00");
        assertThat(response.ledgerDelta()).isEqualByComparingTo("0.00");
    }

    private IngestCardSettlementReportRequest request(IngestCardSettlementReportRequest.Line line) {
        return new IngestCardSettlementReportRequest(
                "mer_1", Mode.TEST, "cprog_1", "report_2026_07_26",
                LocalDate.of(2026, 7, 26), "USD", List.of(line));
    }

    private IngestCardSettlementReportRequest.Line line(String railPaymentId, String amount,
                                                       String currency, String movementType) {
        return new IngestCardSettlementReportRequest.Line(
                railPaymentId, "issuer_txn_1", movementType, new BigDecimal(amount), currency);
    }

    private CardProgram program() {
        return new CardProgram(
                "cprog_1", "mer_1", Mode.TEST, "ip_1", "Program", "USD",
                "{}", CardProgramStatus.ACTIVE, CardProgramSystemOfRecord.INTERNAL,
                CardProgramFundingModel.PREFUNDED_CARD_BALANCE, "{}", "{}", "{}",
                null, "ext_prog_1", "ext_funding_1", Instant.now(), Instant.now());
    }

    private CardClearingEvent clearing(String railPaymentId, String amount, String currency, String movementType) {
        return new CardClearingEvent(
                "cclr_1", "evt_1", railPaymentId, null, "RAIL_SIM", "auth_1",
                movementType, "card_1", "cauth_1", "mer_1", Mode.TEST,
                new BigDecimal(amount), currency, "MATCHED", Instant.now());
    }

    private VirtualCard card(String programId) {
        return new VirtualCard(
                "card_1", "ctok_1", "999999****1234", "999999",
                "ac_card", "ac_hold", "ac_wallet", programId, "ip_1", "ch_1",
                "ext_card_1", "ctok_1", VirtualCardStatus.ACTIVE,
                new BigDecimal("500.00"), "USD", null, Instant.now(), Instant.now());
    }
}
