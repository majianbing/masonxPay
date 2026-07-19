package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class VccCloseSweepPostingRule implements PostingRule<VccCloseSweepPostingRule.CloseSweepEvent> {

    private final SnowflakeIdGenerator idGen;

    public VccCloseSweepPostingRule(SnowflakeIdGenerator idGen) {
        this.idGen = idGen;
    }

    @Override
    public List<LedgerPostingCommand> build(CloseSweepEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.CARD_CLOSE_TRANSACTION.prefix());
        // Liability moves card -> wallet: DR PREPAID_CARD (liability down) / CR WALLET (up).
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.card().vccAccountId(), Direction.DEBIT,
                        event.amount(), event.card().currency(), txId),
                new AccountingEntryDraft(event.ownerAccount().ledgerAccountId(), Direction.CREDIT,
                        event.amount(), event.card().currency(), txId)
        ), TransactionType.INTERNAL, "Close card sweep " + event.card().cardId(), null,
                LocalDate.now(), event.ownerAccount().mode(), event.ownerAccount().orgId(),
                event.ownerAccount().merchantId()));
    }

    public record CloseSweepEvent(
            VirtualCard card,
            LedgerAccount ownerAccount,
            BigDecimal amount
    ) {
    }
}
