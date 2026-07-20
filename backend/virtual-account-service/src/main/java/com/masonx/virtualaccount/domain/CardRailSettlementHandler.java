package com.masonx.virtualaccount.domain;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.CardSettlementPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.RailSettlementPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.inbound.InboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;


/**
 * Posts double-entry ledger journals for rail settlement events.
 *
 * <p>Handles four movement types:
 * <ul>
 *   <li><b>CARD_SALE</b> — DR PREPAID_CARD_HOLD / CR card network settlement account.
 *   <li><b>CARD_REVERSAL</b> — DR PREPAID_CARD_HOLD / CR PREPAID_CARD (hold released).
 *   <li><b>BANK_CREDIT_TRANSFER</b> — DR BANK_RAIL_RECEIVABLE / CR merchant WALLET,
 *       recouping any open MERCHANT_RECEIVABLE balance first.
 *   <li><b>BANK_RETURN</b> — DR merchant WALLET up to its balance, shortfall booked
 *       DR MERCHANT_RECEIVABLE (created on first need) / CR BANK_RAIL_RECEIVABLE.
 * </ul>
 *
 * <p>Idempotency: {@link LedgerFacade#postAllIfNew} uses the event envelope ID as the
 * idempotency key. A duplicate Kafka delivery produces no second journal entry.
 *
 * <p>Failure discipline: an event that cannot post is never dropped. Lookup misses
 * (unknown card, missing wallet/receivable account) and posting-time business
 * failures park the event in {@code settlement_exception} for the ops retry
 * workflow. This method is deliberately NOT transactional: each posting is atomic
 * inside the facade, and a posting failure must not roll back the park record.
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
    private final SettlementExceptionService settlementExceptions;
    private final InboxRepository inbox;
    private final SnowflakeIdGenerator idGen;

    public CardRailSettlementHandler(VirtualCardRepository virtualCardRepo,
                                     LedgerAccountRepository accountRepo,
                                     LedgerFacade ledger,
                                     CardSettlementPostingRule cardSettlementPostingRule,
                                     RailSettlementPostingRule railSettlementPostingRule,
                                     SettlementExceptionService settlementExceptions,
                                     InboxRepository inbox,
                                     SnowflakeIdGenerator idGen) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.cardSettlementPostingRule = cardSettlementPostingRule;
        this.railSettlementPostingRule = railSettlementPostingRule;
        this.settlementExceptions = settlementExceptions;
        this.inbox = inbox;
        this.idGen = idGen;
    }

    public void handle(RailSettlementEvent event) {
        String eventId = event.envelope().eventId();
        MoneyMovementType type = event.movementType();

        log.info("CardRailSettlementHandler: eventId={} type={} paymentId={} amount={}",
                eventId, type, event.railPaymentId(), event.amount());

        try {
            if (inbox.hasProcessed(eventId)) {
                log.info("Rail settlement duplicate already processed: eventId={} type={}", eventId, type);
                return;
            }
            switch (type) {
                case CARD_SALE     -> handleCardSale(event, eventId);
                case CARD_REVERSAL -> handleCardReversal(event, eventId);
                case BANK_CREDIT_TRANSFER -> handleBankTransferSettled(event, eventId);
                case BANK_RETURN   -> handleBankReturn(event, eventId);
                default -> park(event, eventId, SettlementExceptionReason.MISSING_EVENT_FIELD,
                        "Unhandled rail movement type: " + type);
            }
        } catch (BusinessException e) {
            // Posting rejected by a business rule (e.g. VA_INSUFFICIENT_BALANCE on a
            // bank return after the merchant spent the funds). The posting transaction
            // — including its inbox reservation — has already rolled back; park so ops
            // can retry once the underlying condition is fixed.
            park(event, eventId, SettlementExceptionService.reasonFor(e),
                    e.code() + ": " + e.getMessage());
        }
    }

    // ── Card sale settlement ──────────────────────────────────────────────────

    private void handleCardSale(RailSettlementEvent event, String eventId) {
        VirtualCard card = findCard(event, eventId);
        if (card == null) return;

        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findById(card.holdAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));

        LedgerAccount receivable = findReceivableAccount(
                event, event.networkName(), LedgerAccountType.CARD_NETWORK_RECEIVABLE, eventId);
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

    private void handleCardReversal(RailSettlementEvent event, String eventId) {
        VirtualCard card = findCard(event, eventId);
        if (card == null) return;

        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findById(card.holdAccountId())
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
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
        LedgerAccount wallet = findMerchantWallet(event, eventId);
        if (wallet == null) return;

        LedgerAccount receivable = findReceivableAccount(
                event, event.networkName(), LedgerAccountType.BANK_RAIL_RECEIVABLE, eventId);
        if (receivable == null) return;

        // Recoupment: an existing MERCHANT_RECEIVABLE with open balance is paid
        // down by this settlement before the remainder credits the wallet.
        LedgerAccount merchantReceivable = accountRepo.findTenantAccount(
                        event.merchantId(), RAIL_MODE, event.asset(), LedgerAccountType.MERCHANT_RECEIVABLE)
                .orElse(null);

        boolean posted = ledger.postAllIfNew(
                railSettlementPostingRule.buildBankTransfer(
                        new RailSettlementPostingRule.BankEvent(
                                event, eventId, wallet, receivable, merchantReceivable)),
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
        LedgerAccount wallet = findMerchantWallet(event, eventId);
        if (wallet == null) return;

        LedgerAccount receivable = findReceivableAccount(
                event, event.networkName(), LedgerAccountType.BANK_RAIL_RECEIVABLE, eventId);
        if (receivable == null) return;

        // Shortfall path: when the wallet cannot cover the full return, the rest
        // is booked as merchant debt. Create the debt account on first need.
        LedgerAccount merchantReceivable = null;
        if (wallet.balance().compareTo(event.amount()) < 0) {
            merchantReceivable = findOrCreateMerchantReceivable(event, wallet);
        }

        boolean posted = ledger.postAllIfNew(
                railSettlementPostingRule.buildBankReturn(
                        new RailSettlementPostingRule.BankEvent(
                                event, eventId, wallet, receivable, merchantReceivable)),
                eventId,
                "rail-bank-return");

        if (posted) {
            log.info("Bank return journal posted: eventId={} merchant={} amount={}",
                    eventId, event.merchantId(), event.amount());
        } else {
            log.info("Bank return duplicate skipped: eventId={}", eventId);
        }
    }

    // ── Lookups — a miss parks the event and returns null ─────────────────────

    private VirtualCard findCard(RailSettlementEvent event, String eventId) {
        if (event.cardTokenId() == null || event.cardTokenId().isBlank()) {
            park(event, eventId, SettlementExceptionReason.MISSING_EVENT_FIELD,
                    "Rail settlement event has no cardTokenId");
            return null;
        }
        return virtualCardRepo.findActiveByCardTokenId(event.cardTokenId()).orElseGet(() -> {
            park(event, eventId, SettlementExceptionReason.CARD_NOT_FOUND,
                    "No active card for cardTokenId=" + event.cardTokenId());
            return null;
        });
    }

    private LedgerAccount findReceivableAccount(RailSettlementEvent event, String networkName,
                                            LedgerAccountType type, String eventId) {
        return accountRepo.findExternalAccount(networkName, event.asset(), type).orElseGet(() -> {
            park(event, eventId, SettlementExceptionReason.RECEIVABLE_ACCOUNT_NOT_FOUND,
                    "Receivable account not found: network=" + networkName
                            + " asset=" + event.asset() + " type=" + type);
            return null;
        });
    }

    private LedgerAccount findMerchantWallet(RailSettlementEvent event, String eventId) {
        if (event.merchantId() == null) {
            park(event, eventId, SettlementExceptionReason.MISSING_EVENT_FIELD,
                    "Rail settlement event has no merchantId");
            return null;
        }
        return accountRepo.findTenantAccount(event.merchantId(), RAIL_MODE, event.asset(),
                        LedgerAccountType.WALLET)
                .orElseGet(() -> {
                    park(event, eventId, SettlementExceptionReason.WALLET_ACCOUNT_NOT_FOUND,
                            "WALLET account not found for merchant=" + event.merchantId()
                                    + " asset=" + event.asset());
                    return null;
                });
    }

    /**
     * One MERCHANT_RECEIVABLE per (merchant, mode, asset), created on first
     * shortfall. Race-safe via the partial unique index + ON CONFLICT DO NOTHING:
     * concurrent creators both end up re-reading the single surviving row.
     */
    private LedgerAccount findOrCreateMerchantReceivable(RailSettlementEvent event, LedgerAccount wallet) {
        return accountRepo.findTenantAccount(
                        event.merchantId(), RAIL_MODE, event.asset(), LedgerAccountType.MERCHANT_RECEIVABLE)
                .orElseGet(() -> {
                    accountRepo.saveIfAbsent(new LedgerAccount(
                            idGen.generate(MasonXIdPrefix.VA_ACCOUNT.prefix()),
                            RAIL_MODE,
                            LedgerAccountRole.TENANT,
                            wallet.orgId(),
                            event.merchantId(),
                            null,
                            LedgerAccountType.MERCHANT_RECEIVABLE,
                            event.asset(),
                            wallet.assetClass(),
                            wallet.scale(),
                            NormalBalance.DEBIT,
                            BigDecimal.ZERO,
                            LedgerAccountStatus.ACTIVE));
                    return accountRepo.findTenantAccount(
                                    event.merchantId(), RAIL_MODE, event.asset(),
                                    LedgerAccountType.MERCHANT_RECEIVABLE)
                            .orElseThrow(() -> new IllegalStateException(
                                    "MERCHANT_RECEIVABLE creation failed for merchant=" + event.merchantId()));
                });
    }

    private void park(RailSettlementEvent event, String eventId,
                      SettlementExceptionReason reason, String detail) {
        settlementExceptions.park(SettlementExceptionSource.RAIL_SETTLEMENT,
                eventId, RailSettlementEvent.TYPE, reason, detail, event);
    }
}
