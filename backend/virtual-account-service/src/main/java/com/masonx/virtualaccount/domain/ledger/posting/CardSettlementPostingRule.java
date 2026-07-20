package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.AccountingDateResolver;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CardSettlementPostingRule {

    private final SnowflakeIdGenerator idGen;
    private final AccountingDateResolver accountingDateResolver;

    @Autowired
    public CardSettlementPostingRule(SnowflakeIdGenerator idGen, AccountingDateResolver accountingDateResolver) {
        this.idGen = idGen;
        this.accountingDateResolver = accountingDateResolver;
    }

    public CardSettlementPostingRule(SnowflakeIdGenerator idGen) {
        this(idGen, new AccountingDateResolver());
    }

    public List<LedgerPostingCommand> buildSale(SaleEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        RailSettlementEvent railEvent = event.event();
        // Held cardholder liability is extinguished and becomes an obligation to the
        // network: DR PREPAID_CARD_HOLD (down) / CR network settlement account (up).
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.holdAccount().ledgerAccountId(), Direction.DEBIT,
                        railEvent.amount(), railEvent.asset(), event.eventId()),
                new AccountingEntryDraft(event.receivableAccount().ledgerAccountId(), Direction.CREDIT,
                        railEvent.amount(), railEvent.asset(), event.eventId())
        ), TransactionType.CARD_SALE, "Card sale " + railEvent.maskedPan(), railEvent.railPaymentId(),
                accountingDateResolver.fromInstant(railEvent.settledAt()),
                event.cardAccount().mode(), event.cardAccount().orgId(),
                event.cardAccount().merchantId()));
    }

    public List<LedgerPostingCommand> buildReversal(ReversalEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        RailSettlementEvent railEvent = event.event();
        // Confirmed reversal releases the hold back to available:
        // DR PREPAID_CARD_HOLD (down) / CR PREPAID_CARD (up).
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.holdAccount().ledgerAccountId(), Direction.DEBIT,
                        railEvent.amount(), railEvent.asset(), event.eventId()),
                new AccountingEntryDraft(event.cardAccount().ledgerAccountId(), Direction.CREDIT,
                        railEvent.amount(), railEvent.asset(), event.eventId())
        ), TransactionType.REVERSAL, "Card auth reversal " + railEvent.maskedPan(), railEvent.railPaymentId(),
                accountingDateResolver.fromInstant(railEvent.settledAt()),
                event.cardAccount().mode(), event.cardAccount().orgId(),
                event.cardAccount().merchantId()));
    }

    public record SaleEvent(
            RailSettlementEvent event,
            String eventId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount holdAccount,
            LedgerAccount receivableAccount
    ) {
    }

    public record ReversalEvent(
            RailSettlementEvent event,
            String eventId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount holdAccount
    ) {
    }
}
