package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class RailSettlementPostingRule {

    private final SnowflakeIdGenerator idGen;

    public RailSettlementPostingRule(SnowflakeIdGenerator idGen) {
        this.idGen = idGen;
    }

    public List<LedgerPostingCommand> buildBankTransfer(BankEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        RailSettlementEvent railEvent = event.event();
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.receivableAccount().ledgerAccountId(), Direction.DEBIT,
                        railEvent.amount(), railEvent.asset(), event.eventId()),
                new AccountingEntryDraft(event.walletAccount().ledgerAccountId(), Direction.CREDIT,
                        railEvent.amount(), railEvent.asset(), event.eventId())
        ), TransactionType.BANK_TRANSFER, "Bank credit transfer " + railEvent.networkName(),
                railEvent.railPaymentId(), LocalDate.now(), Mode.TEST, null, railEvent.merchantId()));
    }

    public List<LedgerPostingCommand> buildBankReturn(BankEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        RailSettlementEvent railEvent = event.event();
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.walletAccount().ledgerAccountId(), Direction.DEBIT,
                        railEvent.amount(), railEvent.asset(), event.eventId()),
                new AccountingEntryDraft(event.receivableAccount().ledgerAccountId(), Direction.CREDIT,
                        railEvent.amount(), railEvent.asset(), event.eventId())
        ), TransactionType.REVERSAL, "Bank return " + railEvent.networkName(),
                railEvent.railPaymentId(), LocalDate.now(), Mode.TEST, null, railEvent.merchantId()));
    }

    public record BankEvent(
            RailSettlementEvent event,
            String eventId,
            LedgerAccount walletAccount,
            LedgerAccount receivableAccount
    ) {
    }
}
