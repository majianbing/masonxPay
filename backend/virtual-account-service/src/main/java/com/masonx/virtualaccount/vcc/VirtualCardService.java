package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.CreateVccRequest;
import com.masonx.virtualaccount.vcc.dto.CreateVccResponse;
import com.masonx.virtualaccount.vcc.dto.FundVccRequest;
import com.masonx.virtualaccount.vcc.dto.VccResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class VirtualCardService {

    private static final String VA_BIN = "999999";

    private final VirtualCardRepository virtualCardRepo;
    private final AccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final SnowflakeIdGenerator  idGen;

    public VirtualCardService(VirtualCardRepository virtualCardRepo,
                               AccountRepository accountRepo,
                               LedgerFacade ledger,
                               SnowflakeIdGenerator idGen) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.idGen           = idGen;
    }

    /**
     * Creates a VA-issued VCC backed by a new PREPAID_CARD account (initial balance = 0).
     * The test PAN is returned once in {@link CreateVccResponse} and never stored.
     */
    @Transactional
    public CreateVccResponse createCard(CreateVccRequest req) {
        // Verify the owner wallet account exists and belongs to this merchant.
        VaAccount ownerAccount = accountRepo.findById(req.ownerAccountId())
                .filter(a -> req.merchantId().equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "WALLET account not found or does not belong to merchant: " + req.ownerAccountId()));

        // Create the ring-fenced PREPAID_CARD account.
        String vccAccountId = idGen.generate("va_");
        VaAccount vccAccount = new VaAccount(
                vccAccountId,
                ownerAccount.mode(),
                AccountRole.TENANT,
                ownerAccount.orgId(),
                req.merchantId(),
                null,
                AccountType.PREPAID_CARD,
                req.currency(),
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                AccountStatus.ACTIVE);
        accountRepo.save(vccAccount);

        // Generate simulator test PAN: BIN 999999 + 10 random digits.
        String testPan   = VA_BIN + randomPanSuffix();
        String maskedPan = VA_BIN + "****" + testPan.substring(testPan.length() - 4);
        LocalDate expiry = req.expiry() != null ? req.expiry() : LocalDate.now().plusYears(1);

        String cardId = idGen.generate("vc_");
        VirtualCard card = new VirtualCard(
                cardId,
                maskedPan,
                VA_BIN,
                vccAccountId,
                req.ownerAccountId(),
                VirtualCardStatus.ACTIVE,
                req.spendingLimit(),
                req.currency(),
                expiry,
                Instant.now(),
                Instant.now());
        virtualCardRepo.save(card);

        return new CreateVccResponse(
                cardId, testPan, maskedPan, VA_BIN,
                req.currency(), expiry.toString());
    }

    /**
     * Transfers {@code amount} from the card's linked WALLET account to its PREPAID_CARD account.
     * Uses the double-entry ledger (DR PREPAID_CARD / CR WALLET).
     */
    @Transactional
    public VccResponse fundCard(String cardId, FundVccRequest req) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .filter(c -> req.merchantId().equals(
                        accountRepo.findById(c.ownerAccountId())
                                .map(VaAccount::merchantId).orElse(null)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card not found or not owned by merchant: " + cardId));

        if (card.status() != VirtualCardStatus.ACTIVE) {
            throw new IllegalStateException("Cannot fund card in status: " + card.status());
        }

        String txId = idGen.generate("tx_fund_");
        PostTransaction tx = new PostTransaction(txId, List.of(
                new EntryDraft(card.vccAccountId(), Direction.DEBIT,
                        req.amount(), card.currency(), txId),
                new EntryDraft(card.ownerAccountId(), Direction.CREDIT,
                        req.amount(), card.currency(), txId)
        ));
        ledger.postDirect(tx);

        return getCard(cardId);
    }

    public VccResponse getCard(String cardId) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
        VaAccount account = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));
        return toResponse(card, account);
    }

    public List<VccResponse> listCards(String merchantId) {
        return virtualCardRepo.findByMerchantId(merchantId).stream()
                .map(card -> {
                    VaAccount acct = accountRepo.findById(card.vccAccountId()).orElse(null);
                    return toResponse(card, acct);
                })
                .toList();
    }

    /**
     * Closes the card: sweeps remaining balance back to the owner WALLET account,
     * then marks the card CLOSED and the PREPAID_CARD account CLOSED.
     */
    @Transactional
    public void closeCard(String cardId, String merchantId) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));

        VaAccount vccAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));

        // Validate ownership via the owner account's merchantId.
        VaAccount ownerAccount = accountRepo.findById(card.ownerAccountId())
                .filter(a -> merchantId.equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card does not belong to merchant: " + merchantId));

        BigDecimal remaining = vccAccount.availableBalance();
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            String txId = idGen.generate("tx_close_");
            ledger.postDirect(new PostTransaction(txId, List.of(
                    new EntryDraft(ownerAccount.accountId(), Direction.DEBIT,
                            remaining, card.currency(), txId),
                    new EntryDraft(card.vccAccountId(), Direction.CREDIT,
                            remaining, card.currency(), txId)
            )));
        }

        virtualCardRepo.updateStatus(cardId, VirtualCardStatus.CLOSED);
        accountRepo.updateBalance(vccAccount.accountId(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static VccResponse toResponse(VirtualCard card, VaAccount account) {
        BigDecimal balance  = account != null ? account.balance()         : BigDecimal.ZERO;
        BigDecimal frozen   = account != null ? account.frozenBalance()   : BigDecimal.ZERO;
        BigDecimal avail    = account != null ? account.availableBalance(): BigDecimal.ZERO;
        return new VccResponse(
                card.cardId(), card.maskedPan(), card.bin(),
                card.status().name(),
                balance, frozen, avail,
                card.spendingLimit(),
                card.currency(),
                card.expiry() != null ? card.expiry().toString() : null);
    }

    private static String randomPanSuffix() {
        return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    }
}
