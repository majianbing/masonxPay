package com.masonx.virtualaccount.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.masonx.common.error.BusinessException;
import com.masonx.contracts.EventEnvelope;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.CardRailSettlementHandler;
import com.masonx.virtualaccount.domain.LedgerSettlementHandler;
import com.masonx.virtualaccount.domain.SettlementExceptionRepository;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionStatus;
import com.masonx.virtualaccount.domain.po.SettlementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementExceptionRetryServiceTest {

    private static final String EXCEPTION_ID = "sexc_1";
    private static final String EVENT_ID = "evt_rail_1";

    @Mock SettlementExceptionRepository repo;
    @Mock CardRailSettlementHandler cardRailHandler;
    @Mock LedgerSettlementHandler ledgerHandler;

    SettlementExceptionRetryService service;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new SettlementExceptionRetryService(repo, cardRailHandler, ledgerHandler, objectMapper);
    }

    @Test
    void retry_redrives_rail_event_and_resolves_when_handler_does_not_repark() throws Exception {
        String payload = objectMapper.writeValueAsString(railEvent());
        SettlementException row = row(payload, SettlementExceptionStatus.OPEN, 1);
        // Same delivery_count after the retry → the handler did not re-park.
        when(repo.findById(EXCEPTION_ID)).thenReturn(Optional.of(row), Optional.of(row));

        boolean resolved = service.retry(EXCEPTION_ID);

        assertThat(resolved).isTrue();
        ArgumentCaptor<RailSettlementEvent> eventCaptor = ArgumentCaptor.forClass(RailSettlementEvent.class);
        verify(cardRailHandler).handle(eventCaptor.capture());
        assertThat(eventCaptor.getValue().envelope().eventId()).isEqualTo(EVENT_ID);
        assertThat(eventCaptor.getValue().amount()).isEqualByComparingTo("200.00");
        verify(repo).markResolved(any(), any());
        verify(repo, never()).incrementRetryCount(any());
    }

    @Test
    void retry_keeps_exception_open_when_handler_reparks_the_event() throws Exception {
        String payload = objectMapper.writeValueAsString(railEvent());
        SettlementException before = row(payload, SettlementExceptionStatus.OPEN, 1);
        SettlementException after  = row(payload, SettlementExceptionStatus.OPEN, 2);
        when(repo.findById(EXCEPTION_ID)).thenReturn(Optional.of(before), Optional.of(after));

        boolean resolved = service.retry(EXCEPTION_ID);

        assertThat(resolved).isFalse();
        verify(repo).incrementRetryCount(EXCEPTION_ID);
        verify(repo, never()).markResolved(any(), any());
    }

    @Test
    void retry_rejects_non_open_exception() {
        when(repo.findById(EXCEPTION_ID)).thenReturn(Optional.of(
                row("{}", SettlementExceptionStatus.RESOLVED, 1)));

        assertThatThrownBy(() -> service.retry(EXCEPTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only OPEN");
        verify(cardRailHandler, never()).handle(any());
    }

    @Test
    void retry_rejects_unknown_source_exception() {
        SettlementException unknown = new SettlementException(
                EXCEPTION_ID, SettlementExceptionSource.UNKNOWN, EVENT_ID, "unknown",
                "{\"raw\":\"garbage\"}", SettlementExceptionReason.UNEXPECTED_ERROR, "boom",
                SettlementExceptionStatus.OPEN, 1, 0, null, Instant.now(), Instant.now());
        when(repo.findById(EXCEPTION_ID)).thenReturn(Optional.of(unknown));

        assertThatThrownBy(() -> service.retry(EXCEPTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("inspect and discard");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static SettlementException row(String payload, SettlementExceptionStatus status,
                                           int deliveryCount) {
        return new SettlementException(
                EXCEPTION_ID, SettlementExceptionSource.RAIL_SETTLEMENT, EVENT_ID,
                RailSettlementEvent.TYPE, payload,
                SettlementExceptionReason.WALLET_ACCOUNT_NOT_FOUND, "wallet missing",
                status, deliveryCount, 0, null, Instant.now(), Instant.now());
    }

    private static RailSettlementEvent railEvent() {
        var envelope = new EventEnvelope(
                EVENT_ID, RailSettlementEvent.TYPE, RailSettlementEvent.SCHEMA_VERSION, Instant.now(), "corr_1", null);
        return new RailSettlementEvent(
                envelope, "pay_1", PaymentRail.BANK_ISO20022, MoneyMovementType.BANK_CREDIT_TRANSFER,
                "USD", new BigDecimal("200.00"), null, null, "SEPA_SIM", Instant.now(),
                "mer_abc", null, null);
    }
}
