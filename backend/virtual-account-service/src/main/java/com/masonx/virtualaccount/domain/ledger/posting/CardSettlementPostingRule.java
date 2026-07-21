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

    // ── Interfaces below are defined for the remaining MoneyMovementType values
    // that CardRailSettlementHandler does not yet post. Each throws until its
    // posting logic is implemented; see the handler for how each type is parked
    // in the meantime. Do not wire these into the handler's live path until the
    // open design questions noted per method are resolved.
    // Remaining concern: these are only lifecycle contracts today. Capture and
    // settlement need a durable linkage model for original authorization,
    // captured amount, released remainder, and final settlement reference before
    // they can safely become value-moving postings.

    /**
     * CARD_AUTH_REVERSAL — cancels an authorization hold before it is captured.
     * Same DR/CR shape as {@link #buildReversal}: {@code DR PREPAID_CARD_HOLD / CR PREPAID_CARD}.
     * This is the typed replacement for the legacy generic {@code CARD_REVERSAL} path
     * (see {@code MoneyMovementType.CARD_REVERSAL} javadoc — "prefer CARD_AUTH_REVERSAL
     * ... for new card rail code"). No open design question; safe to implement directly
     * by mirroring {@link #buildReversal}.
     */
    public List<LedgerPostingCommand> buildAuthReversal(AuthReversalEvent event) {
        throw new UnsupportedOperationException(
                "CARD_AUTH_REVERSAL posting not yet implemented — see class javadoc");
    }

    /**
     * CARD_SALE_REVERSAL — reverses a financial sale that has already been captured
     * (the hold is already extinguished into {@code CARD_NETWORK_RECEIVABLE} via
     * {@link #buildSale}). Mirror-image of {@link #buildSale}:
     * {@code DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD}. Needs a reference to the
     * original sale's {@code railPaymentId}/transaction id for audit linkage — add that
     * field to {@link AuthReversalEvent}'s sibling record before implementing.
     */
    public List<LedgerPostingCommand> buildSaleReversal(SaleReversalEvent event) {
        throw new UnsupportedOperationException(
                "CARD_SALE_REVERSAL posting not yet implemented — see class javadoc");
    }

    /**
     * CARD_CAPTURE — gateway-facing capture/completion in a dual-message flow
     * (auth now, capture later — as opposed to {@link #buildSale}'s combined
     * 0200-style single-message flow).
     *
     * <p><b>Open design question:</b> capture amount may be less than the original
     * authorized hold (partial capture — common for delayed-amount merchants such as
     * hotels/rentals). A correct implementation likely needs a 3-leg posting:
     * {@code DR PREPAID_CARD_HOLD (full authorized amount) / CR CARD_NETWORK_RECEIVABLE
     * (captured amount) / CR PREPAID_CARD (uncaptured remainder, released back to
     * available)}. Decide the partial-capture product behavior (release remainder
     * immediately vs. leave it held pending expiry) before implementing.
     */
    public List<LedgerPostingCommand> buildCapture(CaptureEvent event) {
        throw new UnsupportedOperationException(
                "CARD_CAPTURE posting not yet implemented — see class javadoc, partial-capture design pending");
    }

    /**
     * CARD_SETTLEMENT — the value-moving leg of a dual-message flow, settling a prior
     * {@code CARD_CAPTURE} rather than the raw auth hold. Per {@code MoneyMovementType}
     * javadoc: "this is where ledger posting/reconciliation should happen" for flows
     * that don't collapse capture and settlement into one {@link #buildSale} event.
     *
     * <p><b>Open design question:</b> depends on {@link #buildCapture}'s data model
     * being decided first — settlement needs to know the captured amount and which
     * capture event it is finalizing, not just the original authorization.
     */
    public List<LedgerPostingCommand> buildSettlement(SettlementCompletionEvent event) {
        throw new UnsupportedOperationException(
                "CARD_SETTLEMENT posting not yet implemented — depends on CARD_CAPTURE design");
    }

    /**
     * CARD_REFUND — refund after a prior settled transaction. Raw entry shape looks
     * like {@link #buildSaleReversal} ({@code DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD}),
     * but it is a distinct business event: independently initiated, not required to be
     * same-day or same-amount as the original sale, and must carry its own transaction
     * lineage back to the original sale for audit rather than literally undoing it.
     */
    public List<LedgerPostingCommand> buildRefund(RefundEvent event) {
        throw new UnsupportedOperationException(
                "CARD_REFUND posting not yet implemented — see class javadoc");
    }

    /**
     * CARD_CREDIT — an original/push credit (e.g. OCT-style rebate or cashback) with
     * no prior authorization or hold at all: {@code DR CARD_NETWORK_RECEIVABLE /
     * CR PREPAID_CARD}, no hold account touched.
     *
     * <p><b>Open design question:</b> unlike a refund, this is not backed by a matching
     * prior debit — it needs its own risk/velocity controls before going live (out of
     * scope for this interface pass).
     */
    public List<LedgerPostingCommand> buildCredit(CreditEvent event) {
        throw new UnsupportedOperationException(
                "CARD_CREDIT posting not yet implemented — needs risk/velocity design first");
    }

    // CARD_CLEARING_PRESENTMENT intentionally has no build* method here. Per
    // MoneyMovementType javadoc it is "used for matching, not online authorization" —
    // it is expected to be a reconciliation/matching input, not a value-moving event,
    // so forcing it into this posting-rule shape would misrepresent it. When this is
    // implemented it likely belongs as a matching hook against existing settlement
    // records, not a new PostingRule method.

    public record AuthReversalEvent(
            RailSettlementEvent event,
            String eventId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount holdAccount
    ) {
    }

    public record SaleReversalEvent(
            RailSettlementEvent event,
            String eventId,
            String originalRailPaymentId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount receivableAccount
    ) {
    }

    public record CaptureEvent(
            RailSettlementEvent event,
            String eventId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount holdAccount,
            LedgerAccount receivableAccount
    ) {
    }

    public record SettlementCompletionEvent(
            RailSettlementEvent event,
            String eventId,
            String captureEventId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount holdAccount,
            LedgerAccount receivableAccount
    ) {
    }

    public record RefundEvent(
            RailSettlementEvent event,
            String eventId,
            String originalRailPaymentId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount receivableAccount
    ) {
    }

    public record CreditEvent(
            RailSettlementEvent event,
            String eventId,
            VirtualCard card,
            LedgerAccount cardAccount,
            LedgerAccount receivableAccount
    ) {
    }
}
