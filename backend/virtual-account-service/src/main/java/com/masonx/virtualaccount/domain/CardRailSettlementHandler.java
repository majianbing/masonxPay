package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.CardSettlementPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.RailSettlementPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * Posts double-entry ledger journals for rail settlement events.
 *
 * <p>Handles four movement types:
 * <ul>
 *   <li><b>CARD_SALE</b> — DR CARD_NETWORK_RECEIVABLE / CR PREPAID_CARD_HOLD.
 *   <li><b>CARD_REVERSAL</b> — DR PREPAID_CARD / CR PREPAID_CARD_HOLD.
 *   <li><b>BANK_CREDIT_TRANSFER</b> — DR BANK_RAIL_RECEIVABLE / CR merchant WALLET.
 *   <li><b>BANK_RETURN</b> — reverses the settlement: DR merchant WALLET / CR BANK_RAIL_RECEIVABLE.
 * </ul>
 *
 * <p>Idempotency: {@link LedgerFacade#postAllIfNew} uses the event envelope ID as the
 * idempotency key. A duplicate Kafka delivery produces no second journal entry.
 *
 * <p>Mode: rail-service operates in TEST mode only (simulator). All account lookups
 * use {@link Mode#TEST}. This will need revisiting if a LIVE rail path is introduced.
 */
@Component
public class CardRailSettlementHandler {

    private static final Logger log = LoggerFactory.getLogger(CardRailSettlementHandler.class);
    private static final Mode   RAIL_MODE = Mode.TEST;

    private final VirtualCardRepository virtualCardRepo;
    private final LedgerAccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final CardSettlementPostingRule cardSettlementPostingRule;
    private final RailSettlementPostingRule railSettlementPostingRule;

    public CardRailSettlementHandler(VirtualCardRepository virtualCardRepo,
                                     LedgerAccountRepository accountRepo,
                                     LedgerFacade ledger,
                                     CardSettlementPostingRule cardSettlementPostingRule,
                                     RailSettlementPostingRule railSettlementPostingRule) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.cardSettlementPostingRule = cardSettlementPostingRule;
        this.railSettlementPostingRule = railSettlementPostingRule;
    }

    @Transactional
    public void handle(RailSettlementEvent event) {
        String eventId = event.envelope().eventId();
        MoneyMovementType type = event.movementType();

        log.info("CardRailSettlementHandler: eventId={} type={} paymentId={} amount={}",
                eventId, type, event.railPaymentId(), event.amount());

        switch (type) {
            case CARD_SALE     -> handleCardSale(event, eventId);
            case CARD_REVERSAL -> handleCardReversal(event);
            case BANK_CREDIT_TRANSFER -> handleBankTransferSettled(event, eventId);
            case BANK_RETURN   -> handleBankReturn(event, eventId);
            default -> log.warn("Unhandled rail movement type={} eventId={}", type, eventId);
        }
    }

    // ── Card sale settlement ──────────────────────────────────────────────────

    private void handleCardSale(RailSettlementEvent event, String eventId) {
        VirtualCard card = findCardByMaskedPan(event.maskedPan(), eventId);
        if (card == null) return;

        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findById(card.holdAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));

        LedgerAccount receivable = findReceivableAccount(
                event.networkName(), event.asset(), LedgerAccountType.CARD_NETWORK_RECEIVABLE, eventId);
        if (receivable == null) return;

        boolean posted = ledger.postAllIfNew(
                cardSettlementPostingRule.buildSale(
                        new CardSettlementPostingRule.SaleEvent(event, eventId, card,
                                cardAccount, holdAccount, receivable)),
                eventId,
                "rail-card-sale");

        if (posted) {
            log.info("Card sale journal posted: eventId={} cardId={} amount={}",
                    eventId, card.cardId(), event.amount());
        } else {
            log.info("Card sale duplicate skipped: eventId={}", eventId);
        }
    }

    // ── Card reversal (0410 confirmed) ────────────────────────────────────────

    private void handleCardReversal(RailSettlementEvent event) {
        // Auth never posts a ledger entry, so reversal has no entry to reverse.
        // Only action: release the frozen amount so available balance is restored.
        String eventId = event.envelope().eventId();

        VirtualCard card = findCardByMaskedPan(event.maskedPan(), eventId);
        if (card == null) return;

        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findById(card.holdAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));

        boolean posted = ledger.postAllIfNew(
                cardSettlementPostingRule.buildReversal(
                        new CardSettlementPostingRule.ReversalEvent(event, eventId, card, cardAccount, holdAccount)),
                eventId,
                "rail-card-reversal");

        if (posted) {
            log.info("Card reversal journal posted: eventId={} cardId={} amount={}",
                    eventId, card.cardId(), event.amount());
        } else {
            log.info("Card reversal duplicate skipped: eventId={}", eventId);
        }
    }

    // ── Bank transfer settled (pacs.002 ACSC) ────────────────────────────────

    private void handleBankTransferSettled(RailSettlementEvent event, String eventId) {
        LedgerAccount wallet = findMerchantWallet(event.merchantId(), event.asset(), eventId);
        if (wallet == null) return;

        LedgerAccount receivable = findReceivableAccount(
                event.networkName(), event.asset(), LedgerAccountType.BANK_RAIL_RECEIVABLE, eventId);
        if (receivable == null) return;

        boolean posted = ledger.postAllIfNew(
                railSettlementPostingRule.buildBankTransfer(
                        new RailSettlementPostingRule.BankEvent(event, eventId, wallet, receivable)),
                eventId,
                "rail-bank-settle");

        if (posted) {
            log.info("Bank transfer journal posted: eventId={} merchant={} amount={}",
                    eventId, event.merchantId(), event.amount());
        } else {
            log.info("Bank transfer duplicate skipped: eventId={}", eventId);
        }
    }

    // ── Bank return (pacs.004) ────────────────────────────────────────────────

    private void handleBankReturn(RailSettlementEvent event, String eventId) {
        LedgerAccount wallet = findMerchantWallet(event.merchantId(), event.asset(), eventId);
        if (wallet == null) return;

        LedgerAccount receivable = findReceivableAccount(
                event.networkName(), event.asset(), LedgerAccountType.BANK_RAIL_RECEIVABLE, eventId);
        if (receivable == null) return;

        boolean posted = ledger.postAllIfNew(
                railSettlementPostingRule.buildBankReturn(
                        new RailSettlementPostingRule.BankEvent(event, eventId, wallet, receivable)),
                eventId,
                "rail-bank-return");

        if (posted) {
            log.info("Bank return journal posted: eventId={} merchant={} amount={}",
                    eventId, event.merchantId(), event.amount());
        } else {
            log.info("Bank return duplicate skipped: eventId={}", eventId);
        }
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    private VirtualCard findCardByMaskedPan(String maskedPan, String eventId) {
        if (maskedPan == null) {
            log.warn("Rail settlement event has no maskedPan — skipping card lookup: eventId={}", eventId);
            return null;
        }
        return virtualCardRepo.findActiveByMaskedPan(maskedPan).orElseGet(() -> {
            log.warn("No active card found for maskedPan={} eventId={}", maskedPan, eventId);
            return null;
        });
    }

    private LedgerAccount findReceivableAccount(String networkName, String asset,
                                            LedgerAccountType type, String eventId) {
        return accountRepo.findExternalAccount(networkName, asset, type).orElseGet(() -> {
            log.error("Receivable account not found: network={} asset={} type={} eventId={}",
                    networkName, asset, type, eventId);
            return null;
        });
    }

    private LedgerAccount findMerchantWallet(String merchantId, String asset, String eventId) {
        if (merchantId == null) {
            log.error("Rail settlement event has no merchantId — cannot post bank journal: eventId={}", eventId);
            return null;
        }
        return accountRepo.findTenantAccount(merchantId, RAIL_MODE, asset, LedgerAccountType.WALLET)
                .orElseGet(() -> {
                    log.error("WALLET account not found for merchant={} asset={} eventId={}",
                            merchantId, asset, eventId);
                    return null;
                });
    }
}
