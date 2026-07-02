package com.masonx.virtualaccount.domain;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.AccountType;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Posts double-entry ledger journals for rail settlement events.
 *
 * <p>Handles four movement types:
 * <ul>
 *   <li><b>CARD_SALE</b> — DR CARD_NETWORK_RECEIVABLE / CR card account (PREPAID_CARD);
 *       releases the frozen amount that was set at auth (0100/0110).
 *   <li><b>CARD_REVERSAL</b> — no ledger entry (auth never posted one);
 *       releases the frozen amount so the card balance is restored.
 *   <li><b>BANK_CREDIT_TRANSFER</b> — DR BANK_RAIL_RECEIVABLE / CR merchant WALLET.
 *   <li><b>BANK_RETURN</b> — reverses the settlement: DR merchant WALLET / CR BANK_RAIL_RECEIVABLE.
 * </ul>
 *
 * <p>Idempotency: {@link LedgerFacade#postIfNew} uses the event envelope ID as the
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
    private final AccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final SnowflakeIdGenerator  idGen;

    public CardRailSettlementHandler(VirtualCardRepository virtualCardRepo,
                                     AccountRepository accountRepo,
                                     LedgerFacade ledger,
                                     SnowflakeIdGenerator idGen) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.idGen           = idGen;
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

        VaAccount cardAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));

        VaAccount receivable = findReceivableAccount(
                event.networkName(), event.asset(), AccountType.CARD_NETWORK_RECEIVABLE, eventId);
        if (receivable == null) return;

        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        boolean posted = ledger.postIfNew(new PostTransaction(txId, List.of(
                new EntryDraft(receivable.accountId(),   Direction.DEBIT,  event.amount(), event.asset(), eventId),
                new EntryDraft(cardAccount.accountId(),  Direction.CREDIT, event.amount(), event.asset(), eventId)
        ), TransactionType.CARD_SALE, "Card sale " + event.maskedPan(), event.railPaymentId(),
                LocalDate.now(), cardAccount.mode(), cardAccount.orgId(), cardAccount.merchantId()),
                eventId, "rail-card-sale");

        if (posted) {
            // Release the freeze set at auth time (0100/0110).
            BigDecimal newFrozen = cardAccount.frozenBalance().subtract(event.amount())
                    .max(BigDecimal.ZERO);
            accountRepo.updateFrozenBalance(cardAccount.accountId(), newFrozen);
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

        VaAccount cardAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));

        BigDecimal newFrozen = cardAccount.frozenBalance().subtract(event.amount())
                .max(BigDecimal.ZERO);
        accountRepo.updateFrozenBalance(cardAccount.accountId(), newFrozen);

        log.info("Card reversal freeze released: eventId={} cardId={} amount={}",
                eventId, card.cardId(), event.amount());
    }

    // ── Bank transfer settled (pacs.002 ACSC) ────────────────────────────────

    private void handleBankTransferSettled(RailSettlementEvent event, String eventId) {
        VaAccount wallet = findMerchantWallet(event.merchantId(), event.asset(), eventId);
        if (wallet == null) return;

        VaAccount receivable = findReceivableAccount(
                event.networkName(), event.asset(), AccountType.BANK_RAIL_RECEIVABLE, eventId);
        if (receivable == null) return;

        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        boolean posted = ledger.postIfNew(new PostTransaction(txId, List.of(
                new EntryDraft(receivable.accountId(), Direction.DEBIT,  event.amount(), event.asset(), eventId),
                new EntryDraft(wallet.accountId(),     Direction.CREDIT, event.amount(), event.asset(), eventId)
        ), TransactionType.BANK_TRANSFER, "Bank credit transfer " + event.networkName(),
                event.railPaymentId(), LocalDate.now(), RAIL_MODE, null, event.merchantId()),
                eventId, "rail-bank-settle");

        if (posted) {
            log.info("Bank transfer journal posted: eventId={} merchant={} amount={}",
                    eventId, event.merchantId(), event.amount());
        } else {
            log.info("Bank transfer duplicate skipped: eventId={}", eventId);
        }
    }

    // ── Bank return (pacs.004) ────────────────────────────────────────────────

    private void handleBankReturn(RailSettlementEvent event, String eventId) {
        VaAccount wallet = findMerchantWallet(event.merchantId(), event.asset(), eventId);
        if (wallet == null) return;

        VaAccount receivable = findReceivableAccount(
                event.networkName(), event.asset(), AccountType.BANK_RAIL_RECEIVABLE, eventId);
        if (receivable == null) return;

        String txId = idGen.generate(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix());
        boolean posted = ledger.postIfNew(new PostTransaction(txId, List.of(
                new EntryDraft(wallet.accountId(),     Direction.DEBIT,  event.amount(), event.asset(), eventId),
                new EntryDraft(receivable.accountId(), Direction.CREDIT, event.amount(), event.asset(), eventId)
        ), TransactionType.REVERSAL, "Bank return " + event.networkName(),
                event.railPaymentId(), LocalDate.now(), RAIL_MODE, null, event.merchantId()),
                eventId, "rail-bank-return");

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

    private VaAccount findReceivableAccount(String networkName, String asset,
                                            AccountType type, String eventId) {
        return accountRepo.findExternalAccount(networkName, asset, type).orElseGet(() -> {
            log.error("Receivable account not found: network={} asset={} type={} eventId={}",
                    networkName, asset, type, eventId);
            return null;
        });
    }

    private VaAccount findMerchantWallet(String merchantId, String asset, String eventId) {
        if (merchantId == null) {
            log.error("Rail settlement event has no merchantId — cannot post bank journal: eventId={}", eventId);
            return null;
        }
        return accountRepo.findTenantAccount(merchantId, RAIL_MODE, asset, AccountType.WALLET)
                .orElseGet(() -> {
                    log.error("WALLET account not found for merchant={} asset={} eventId={}",
                            merchantId, asset, eventId);
                    return null;
                });
    }
}
