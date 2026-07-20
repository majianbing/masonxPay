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
public class VccFundingPostingRule implements PostingRule<VccFundingPostingRule.FundingEvent> {

    private final SnowflakeIdGenerator idGen;
    private final AccountingDateResolver accountingDateResolver;

    @Autowired
    public VccFundingPostingRule(SnowflakeIdGenerator idGen, AccountingDateResolver accountingDateResolver) {
        this.idGen = idGen;
        this.accountingDateResolver = accountingDateResolver;
    }

    public VccFundingPostingRule(SnowflakeIdGenerator idGen) {
        this(idGen, new AccountingDateResolver());
    }

    @Override
    public List<LedgerPostingCommand> build(FundingEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.CARD_FUND_TRANSACTION.prefix());
        // Liability moves wallet -> card: DR WALLET (liability down) / CR PREPAID_CARD (up).
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.card().ownerAccountId(), Direction.DEBIT,
                        event.amount(), event.card().currency(), event.eventId()),
                new AccountingEntryDraft(event.card().vccAccountId(), Direction.CREDIT,
                        event.amount(), event.card().currency(), event.eventId())
        ), TransactionType.INTERNAL, "Fund card " + event.card().cardId(), null,
                accountingDateResolver.today(), event.ownerAccount().mode(), event.ownerAccount().orgId(),
                event.ownerAccount().merchantId()));
    }

    public record FundingEvent(
            VirtualCard card,
            LedgerAccount ownerAccount,
            BigDecimal amount,
            String eventId
    ) {
    }
}
