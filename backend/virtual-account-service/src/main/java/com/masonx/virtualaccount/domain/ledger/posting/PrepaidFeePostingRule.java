package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingDateResolver;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class PrepaidFeePostingRule implements PostingRule<PrepaidFeePostingRule.PrepaidFeePostingEvent> {

    private final SnowflakeIdGenerator idGen;
    private final AccountingDateResolver accountingDateResolver;

    @Autowired
    public PrepaidFeePostingRule(SnowflakeIdGenerator idGen, AccountingDateResolver accountingDateResolver) {
        this.idGen = idGen;
        this.accountingDateResolver = accountingDateResolver;
    }

    public PrepaidFeePostingRule(SnowflakeIdGenerator idGen) {
        this(idGen, new AccountingDateResolver());
    }

    @Override
    public List<LedgerPostingCommand> build(PrepaidFeePostingEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.FEE_TRANSACTION.prefix());
        PrepaidFeeAssessment assessment = event.assessment();
        return List.of(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(event.debitAccount().ledgerAccountId(), Direction.DEBIT,
                        event.amount(), event.currency(), event.postingEventId(), "merchant_fee"),
                new AccountingEntryDraft(event.feeIncomeAccount().ledgerAccountId(), Direction.CREDIT,
                        event.amount(), event.currency(), event.postingEventId(), "platform_fee_income")
        ), TransactionType.FEE, "Prepaid fee " + assessment.eventType() + " " + assessment.eventId(),
                assessment.eventId(), accountingDateResolver.today(), assessment.mode(),
                event.debitAccount().orgId(), assessment.merchantId()));
    }

    public record PrepaidFeePostingEvent(
            PrepaidFeeAssessment assessment,
            LedgerAccount debitAccount,
            LedgerAccount feeIncomeAccount,
            BigDecimal amount,
            String currency,
            String postingEventId
    ) {
    }
}
