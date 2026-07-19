package com.masonx.virtualaccount.domain.ledger.posting;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class GatewaySettlementPostingRule implements PostingRule<GatewaySettlementPostingRule.SettlementEvent> {

    private final LedgerAccountRepository accountRepo;
    private final SnowflakeIdGenerator idGen;

    public GatewaySettlementPostingRule(LedgerAccountRepository accountRepo, SnowflakeIdGenerator idGen) {
        this.accountRepo = accountRepo;
        this.idGen = idGen;
    }

    @Override
    public List<LedgerPostingCommand> build(SettlementEvent event) {
        RecordSettlementCommand cmd = event.command();
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_TRANSACTION.prefix());
        boolean moneyIn = cmd.direction() == Direction.CREDIT;
        TransactionType entryType = moneyIn ? TransactionType.SETTLEMENT : TransactionType.REFUND;
        String description = (moneyIn ? "Settlement " : "Refund ") + cmd.providerRef();
        String orgId = cmd.tenant().orgId() != null ? cmd.tenant().orgId().value().toString() : null;
        String paymentRefId = cmd.paymentId() != null ? cmd.paymentId().toString() : null;

        return List.of(new LedgerPostingCommand(txId,
                buildEntries(cmd, event.tenantCash(), event.externalClearing()),
                entryType, description, paymentRefId, LocalDate.now(),
                cmd.tenant().mode(), orgId, event.merchantId()));
    }

    private List<AccountingEntryDraft> buildEntries(RecordSettlementCommand cmd,
                                                    LedgerAccount tenantCash,
                                                    LedgerAccount externalClearing) {
        boolean moneyIn = cmd.direction() == Direction.CREDIT;
        if (!moneyIn) {
            return List.of(
                    new AccountingEntryDraft(tenantCash.ledgerAccountId(), Direction.CREDIT,
                            cmd.amount(), cmd.asset(), cmd.sourceEventId()),
                    new AccountingEntryDraft(externalClearing.ledgerAccountId(), Direction.DEBIT,
                            cmd.amount(), cmd.asset(), cmd.sourceEventId())
            );
        }

        boolean hasFee = cmd.feeAmount().compareTo(BigDecimal.ZERO) > 0;
        if (hasFee) {
            var platformFeeOpt = accountRepo.findPlatformAccount(cmd.asset(), LedgerAccountType.PLATFORM_FEE_RECEIVABLE);
            if (platformFeeOpt.isPresent()) {
                return List.of(
                        new AccountingEntryDraft(tenantCash.ledgerAccountId(), Direction.DEBIT,
                                cmd.netAmount(), cmd.asset(), cmd.sourceEventId()),
                        new AccountingEntryDraft(platformFeeOpt.get().ledgerAccountId(), Direction.DEBIT,
                                cmd.feeAmount(), cmd.asset(), cmd.sourceEventId()),
                        new AccountingEntryDraft(externalClearing.ledgerAccountId(), Direction.CREDIT,
                                cmd.amount(), cmd.asset(), cmd.sourceEventId())
                );
            }
        }

        BigDecimal net = hasFee ? cmd.netAmount() : cmd.amount();
        List<AccountingEntryDraft> entries = new ArrayList<>();
        entries.add(new AccountingEntryDraft(tenantCash.ledgerAccountId(), Direction.DEBIT,
                net, cmd.asset(), cmd.sourceEventId()));
        entries.add(new AccountingEntryDraft(externalClearing.ledgerAccountId(), Direction.CREDIT,
                net, cmd.asset(), cmd.sourceEventId()));
        return entries;
    }

    public record SettlementEvent(
            RecordSettlementCommand command,
            String merchantId,
            LedgerAccount tenantCash,
            LedgerAccount externalClearing
    ) {
    }
}
