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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Bank-rail settlement journals under platform-books convention: merchant WALLET
 * is a CREDIT-normal platform liability; BANK_RAIL_RECEIVABLE is a DEBIT-normal
 * platform asset (money sitting at the rail); MERCHANT_RECEIVABLE is a
 * DEBIT-normal platform asset (merchant debt from return shortfalls).
 */
@Component
public class RailSettlementPostingRule {

    private final SnowflakeIdGenerator idGen;

    public RailSettlementPostingRule(SnowflakeIdGenerator idGen) {
        this.idGen = idGen;
    }

    /**
     * Inbound credit transfer (pacs.002 ACSC). Money arrives at the rail and is
     * owed to the merchant: DR BANK_RAIL_RECEIVABLE / CR WALLET. If the merchant
     * carries open debt, the settlement recoups it first
     * (CR MERCHANT_RECEIVABLE) and only the remainder reaches the wallet.
     */
    public List<LedgerPostingCommand> buildBankTransfer(BankEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        RailSettlementEvent railEvent = event.event();
        BigDecimal amount = railEvent.amount();

        BigDecimal openDebt = event.merchantReceivable() != null
                ? event.merchantReceivable().balance()
                : BigDecimal.ZERO;
        BigDecimal recouped = openDebt.min(amount).max(BigDecimal.ZERO);
        BigDecimal toWallet = amount.subtract(recouped);

        List<AccountingEntryDraft> entries = new ArrayList<>();
        entries.add(new AccountingEntryDraft(event.receivableAccount().ledgerAccountId(),
                Direction.DEBIT, amount, railEvent.asset(), event.eventId()));
        if (recouped.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new AccountingEntryDraft(event.merchantReceivable().ledgerAccountId(),
                    Direction.CREDIT, recouped, railEvent.asset(), event.eventId()));
        }
        if (toWallet.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new AccountingEntryDraft(event.walletAccount().ledgerAccountId(),
                    Direction.CREDIT, toWallet, railEvent.asset(), event.eventId()));
        }

        return List.of(new LedgerPostingCommand(txId, entries,
                TransactionType.BANK_TRANSFER, "Bank credit transfer " + railEvent.networkName(),
                railEvent.railPaymentId(), LocalDate.now(), Mode.TEST, null, railEvent.merchantId()));
    }

    /**
     * Bank return (pacs.004) — always postable. Claws back from the wallet up to
     * its available balance; any shortfall is booked as merchant debt:
     *
     * <pre>
     *   covered:    DR WALLET amount                          / CR BANK_RAIL_RECEIVABLE amount
     *   shortfall:  DR WALLET available + DR MERCHANT_RECEIVABLE rest / CR BANK_RAIL_RECEIVABLE amount
     *   empty:      DR MERCHANT_RECEIVABLE amount             / CR BANK_RAIL_RECEIVABLE amount
     * </pre>
     *
     * The wallet balance is read outside the posting locks; a concurrent spend
     * can invalidate the split, in which case the engine rejects and the event
     * parks for retry — rare, visible, self-correcting.
     */
    public List<LedgerPostingCommand> buildBankReturn(BankEvent event) {
        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        RailSettlementEvent railEvent = event.event();
        BigDecimal amount = railEvent.amount();

        BigDecimal available = event.walletAccount().balance().max(BigDecimal.ZERO);
        BigDecimal fromWallet = available.min(amount);
        BigDecimal shortfall = amount.subtract(fromWallet);

        if (shortfall.compareTo(BigDecimal.ZERO) > 0 && event.merchantReceivable() == null) {
            throw new IllegalStateException(
                    "Bank return shortfall requires a MERCHANT_RECEIVABLE account: eventId=" + event.eventId());
        }

        List<AccountingEntryDraft> entries = new ArrayList<>();
        if (fromWallet.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new AccountingEntryDraft(event.walletAccount().ledgerAccountId(),
                    Direction.DEBIT, fromWallet, railEvent.asset(), event.eventId()));
        }
        if (shortfall.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new AccountingEntryDraft(event.merchantReceivable().ledgerAccountId(),
                    Direction.DEBIT, shortfall, railEvent.asset(), event.eventId()));
        }
        entries.add(new AccountingEntryDraft(event.receivableAccount().ledgerAccountId(),
                Direction.CREDIT, amount, railEvent.asset(), event.eventId()));

        return List.of(new LedgerPostingCommand(txId, entries,
                TransactionType.REVERSAL, "Bank return " + railEvent.networkName(),
                railEvent.railPaymentId(), LocalDate.now(), Mode.TEST, null, railEvent.merchantId()));
    }

    public record BankEvent(
            RailSettlementEvent event,
            String eventId,
            LedgerAccount walletAccount,
            LedgerAccount receivableAccount,
            LedgerAccount merchantReceivable   // nullable: required only when debt is booked/recouped
    ) {
    }
}
