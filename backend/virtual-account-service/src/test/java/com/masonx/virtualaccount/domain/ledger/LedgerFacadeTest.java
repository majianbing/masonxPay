package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.inbound.InboxRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerFacadeTest {

    @Test
    void postAllIfNew_posts_every_command_after_single_inbox_reservation() {
        LedgerPostingService engine = mock(LedgerPostingService.class);
        InboxRepository inbox = mock(InboxRepository.class);
        LedgerFacade facade = new LedgerFacade(engine, inbox);
        LedgerPostingCommand first = command("tx_1", "evt_1");
        LedgerPostingCommand second = command("tx_2", "evt_1");

        when(inbox.markProcessed("evt_1", "settlement")).thenReturn(true);

        facade.postAllIfNew(List.of(first, second), "evt_1", "settlement");

        verify(inbox).markProcessed("evt_1", "settlement");
        verify(engine).post(first);
        verify(engine).post(second);
    }

    @Test
    void postAllIfNew_skips_all_commands_when_event_is_duplicate() {
        LedgerPostingService engine = mock(LedgerPostingService.class);
        InboxRepository inbox = mock(InboxRepository.class);
        LedgerFacade facade = new LedgerFacade(engine, inbox);

        when(inbox.markProcessed("evt_1", "settlement")).thenReturn(false);

        facade.postAllIfNew(List.of(command("tx_1", "evt_1")), "evt_1", "settlement");

        verify(inbox).markProcessed("evt_1", "settlement");
        verify(engine, never()).post(org.mockito.ArgumentMatchers.any());
    }

    private static LedgerPostingCommand command(String transactionId, String sourceEventId) {
        return new LedgerPostingCommand(
                transactionId,
                List.of(
                        new AccountingEntryDraft("ac_debit", Direction.DEBIT,
                                new BigDecimal("10.00"), "USD", sourceEventId),
                        new AccountingEntryDraft("ac_credit", Direction.CREDIT,
                                new BigDecimal("10.00"), "USD", sourceEventId)),
                TransactionType.INTERNAL,
                null,
                null,
                LocalDate.of(2026, 1, 1),
                Mode.LIVE,
                "org_1",
                "mer_1");
    }
}
