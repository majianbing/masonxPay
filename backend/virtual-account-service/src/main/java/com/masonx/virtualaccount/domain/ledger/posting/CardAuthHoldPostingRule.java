package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.AccountingDateResolver;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class CardAuthHoldPostingRule implements PostingRule<CardAuthHoldPostingRule.AuthHoldEvent> {

    private final SnowflakeIdGenerator idGen;
    private final AccountingDateResolver accountingDateResolver;

    @Autowired
    public CardAuthHoldPostingRule(SnowflakeIdGenerator idGen, AccountingDateResolver accountingDateResolver) {
        this.idGen = idGen;
        this.accountingDateResolver = accountingDateResolver;
    }

    public CardAuthHoldPostingRule(SnowflakeIdGenerator idGen) {
        this(idGen, new AccountingDateResolver());
    }

    @Override
    public List<LedgerPostingCommand> build(AuthHoldEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        // Liability moves available -> held: DR PREPAID_CARD (down) / CR PREPAID_CARD_HOLD (up).
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.cardAccount().ledgerAccountId(), Direction.DEBIT,
                        event.amount(), event.currency(), event.eventId()),
                new AccountingEntryDraft(event.holdAccount().ledgerAccountId(), Direction.CREDIT,
                        event.amount(), event.currency(), event.eventId())
        ), TransactionType.INTERNAL, "Card auth hold " + event.card().cardId(), null,
                accountingDateResolver.today(), event.cardAccount().mode(), event.cardAccount().orgId(),
                event.cardAccount().merchantId()));
    }

    public record AuthHoldEvent(
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount holdAccount,
            BigDecimal amount,
            String currency,
            String eventId
    ) {
    }
}
