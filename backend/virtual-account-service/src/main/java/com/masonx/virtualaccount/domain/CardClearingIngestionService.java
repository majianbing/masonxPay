package com.masonx.virtualaccount.domain;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.CardSettlementPostingRule;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import com.masonx.virtualaccount.domain.po.CardClearingEvent;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class CardClearingIngestionService {

    private final VirtualCardRepository virtualCardRepo;
    private final CardAuthorizationRepository authorizationRepo;
    private final CardClearingEventRepository clearingEventRepo;
    private final LedgerAccountRepository accountRepo;
    private final LedgerFacade ledger;
    private final CardSettlementPostingRule cardSettlementPostingRule;
    private final SnowflakeIdGenerator idGen;

    public CardClearingIngestionService(VirtualCardRepository virtualCardRepo,
                                        CardAuthorizationRepository authorizationRepo,
                                        CardClearingEventRepository clearingEventRepo,
                                        LedgerAccountRepository accountRepo,
                                        LedgerFacade ledger,
                                        CardSettlementPostingRule cardSettlementPostingRule,
                                        SnowflakeIdGenerator idGen) {
        this.virtualCardRepo = virtualCardRepo;
        this.authorizationRepo = authorizationRepo;
        this.clearingEventRepo = clearingEventRepo;
        this.accountRepo = accountRepo;
        this.ledger = ledger;
        this.cardSettlementPostingRule = cardSettlementPostingRule;
        this.idGen = idGen;
    }

    @Transactional
    public CardClearingIngestionResult ingest(RailSettlementEvent event, String eventId) {
        if (event.movementType() == MoneyMovementType.CARD_REFUND) {
            return ingestRefund(event, eventId);
        }
        if (event.movementType() == MoneyMovementType.CARD_CREDIT) {
            return ingestOriginalCredit(event, eventId);
        }
        return ingestClearingPresentment(event, eventId);
    }

    private CardClearingIngestionResult ingestClearingPresentment(RailSettlementEvent event, String eventId) {
        VirtualCard card = findCard(event);
        if (card == null) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.CARD_NOT_FOUND,
                    "No active card for cardTokenId=" + event.cardTokenId());
        }

        CardAuthorization auth = findMatchingAuthorization(event, card);
        if (auth == null) {
            SettlementExceptionReason reason = authorizationRepo.hasOpenHoldForCard(card.cardId(), event.asset())
                    ? SettlementExceptionReason.AMOUNT_MISMATCH
                    : SettlementExceptionReason.AUTHORIZATION_NOT_FOUND;
            return CardClearingIngestionResult.park(reason,
                    "No exact open authorization hold match for cardId=" + card.cardId()
                            + " amount=" + event.amount() + " asset=" + event.asset());
        }

        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findById(card.holdAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));
        LedgerAccount receivable = accountRepo
                .findExternalAccount(event.networkName(), event.asset(), LedgerAccountType.CARD_NETWORK_RECEIVABLE)
                .orElse(null);
        if (receivable == null) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.RECEIVABLE_ACCOUNT_NOT_FOUND,
                    "Receivable account not found: network=" + event.networkName()
                            + " asset=" + event.asset() + " type=" + LedgerAccountType.CARD_NETWORK_RECEIVABLE);
        }

        boolean posted = ledger.postAllIfNew(
                cardSettlementPostingRule.buildSale(
                        new CardSettlementPostingRule.SaleEvent(event, eventId, card,
                                cardAccount, holdAccount, receivable)),
                eventId,
                "rail-card-clearing");
        if (!posted) {
            return CardClearingIngestionResult.duplicate();
        }

        BigDecimal settledTotal = safeAmount(auth.settledAmount()).add(event.amount());
        authorizationRepo.recordClearingSettlement(
                auth.authId(), settledTotal, CardAuthorizationStatus.SETTLED, event.settledAt());
        clearingEventRepo.insert(new CardClearingEvent(
                idGen.generate(MasonXIdPrefix.CARD_CLEARING_EVENT.prefix()),
                eventId,
                event.railPaymentId(),
                event.originalRailPaymentId(),
                event.issuerId(),
                event.originalAuthorizationId(),
                event.movementType().name(),
                card.cardId(),
                auth.authId(),
                cardAccount.merchantId(),
                cardAccount.mode(),
                event.amount(),
                event.asset(),
                "MATCHED",
                event.settledAt()));
        return CardClearingIngestionResult.matched();
    }

    private CardClearingIngestionResult ingestRefund(RailSettlementEvent event, String eventId) {
        if (event.originalRailPaymentId() == null || event.originalRailPaymentId().isBlank()) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.MISSING_EVENT_FIELD,
                    "CARD_REFUND has no originalRailPaymentId");
        }
        CardClearingEvent original = clearingEventRepo
                .findMatchedByRailPaymentIdForUpdate(event.originalRailPaymentId())
                .orElse(null);
        if (original == null) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.ORIGINAL_TRANSACTION_NOT_FOUND,
                    "No matched clearing event for originalRailPaymentId=" + event.originalRailPaymentId());
        }
        BigDecimal alreadyRefunded = clearingEventRepo
                .sumMatchedRefundAmountForOriginalRailPaymentId(event.originalRailPaymentId());
        if (safeAmount(alreadyRefunded).add(event.amount()).compareTo(original.amount()) > 0) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.AMOUNT_MISMATCH,
                    "Refund amount exceeds original clearing amount originalRailPaymentId="
                            + event.originalRailPaymentId());
        }

        VirtualCard card = virtualCardRepo.findById(original.cardId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "Card not found for original clearing: " + original.cardId()));
        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount receivable = accountRepo
                .findExternalAccount(event.networkName(), event.asset(), LedgerAccountType.CARD_NETWORK_RECEIVABLE)
                .orElse(null);
        if (receivable == null) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.RECEIVABLE_ACCOUNT_NOT_FOUND,
                    "Receivable account not found: network=" + event.networkName()
                            + " asset=" + event.asset() + " type=" + LedgerAccountType.CARD_NETWORK_RECEIVABLE);
        }

        boolean posted = ledger.postAllIfNew(
                cardSettlementPostingRule.buildRefund(
                        new CardSettlementPostingRule.RefundEvent(
                                event, eventId, event.originalRailPaymentId(), card, cardAccount, receivable)),
                eventId,
                "rail-card-refund");
        if (!posted) {
            return CardClearingIngestionResult.duplicate();
        }

        clearingEventRepo.insert(new CardClearingEvent(
                idGen.generate(MasonXIdPrefix.CARD_CLEARING_EVENT.prefix()),
                eventId,
                event.railPaymentId(),
                event.originalRailPaymentId(),
                event.issuerId(),
                event.originalAuthorizationId(),
                event.movementType().name(),
                card.cardId(),
                original.authId(),
                cardAccount.merchantId(),
                cardAccount.mode(),
                event.amount(),
                event.asset(),
                "MATCHED",
                event.settledAt()));
        return CardClearingIngestionResult.matched();
    }

    private CardClearingIngestionResult ingestOriginalCredit(RailSettlementEvent event, String eventId) {
        VirtualCard card = findCard(event);
        if (card == null) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.CARD_NOT_FOUND,
                    "No active card for cardTokenId=" + event.cardTokenId());
        }
        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount receivable = accountRepo
                .findExternalAccount(event.networkName(), event.asset(), LedgerAccountType.CARD_NETWORK_RECEIVABLE)
                .orElse(null);
        if (receivable == null) {
            return CardClearingIngestionResult.park(SettlementExceptionReason.RECEIVABLE_ACCOUNT_NOT_FOUND,
                    "Receivable account not found: network=" + event.networkName()
                            + " asset=" + event.asset() + " type=" + LedgerAccountType.CARD_NETWORK_RECEIVABLE);
        }

        boolean posted = ledger.postAllIfNew(
                cardSettlementPostingRule.buildCredit(
                        new CardSettlementPostingRule.CreditEvent(event, eventId, card, cardAccount, receivable)),
                eventId,
                "rail-card-credit");
        if (!posted) {
            return CardClearingIngestionResult.duplicate();
        }

        clearingEventRepo.insert(new CardClearingEvent(
                idGen.generate(MasonXIdPrefix.CARD_CLEARING_EVENT.prefix()),
                eventId,
                event.railPaymentId(),
                event.originalRailPaymentId(),
                event.issuerId(),
                event.originalAuthorizationId(),
                event.movementType().name(),
                card.cardId(),
                null,
                cardAccount.merchantId(),
                cardAccount.mode(),
                event.amount(),
                event.asset(),
                "MATCHED",
                event.settledAt()));
        return CardClearingIngestionResult.matched();
    }

    private CardAuthorization findMatchingAuthorization(RailSettlementEvent event, VirtualCard card) {
        if (event.issuerId() != null && !event.issuerId().isBlank()
                && event.originalAuthorizationId() != null && !event.originalAuthorizationId().isBlank()) {
            CardAuthorization linked = authorizationRepo
                    .findLinkedOpenHoldForUpdate(event.issuerId(), event.originalAuthorizationId(), card.cardId())
                    .orElse(null);
            if (linked == null) {
                return null;
            }
            BigDecimal remaining = linked.amount()
                    .subtract(safeAmount(linked.releasedAmount()))
                    .subtract(safeAmount(linked.settledAmount()));
            return remaining.compareTo(event.amount()) == 0 ? linked : null;
        }
        return authorizationRepo
                .findExactOpenHoldMatchForUpdate(card.cardId(), event.asset(), event.amount())
                .orElse(null);
    }

    private VirtualCard findCard(RailSettlementEvent event) {
        if (event.cardTokenId() == null || event.cardTokenId().isBlank()) {
            return null;
        }
        return virtualCardRepo.findActiveByCardTokenId(event.cardTokenId()).orElse(null);
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
