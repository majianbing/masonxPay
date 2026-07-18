package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.VccCloseSweepPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.VccFundingPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.CreateVccRequest;
import com.masonx.virtualaccount.vcc.dto.CreateVccResponse;
import com.masonx.virtualaccount.vcc.dto.FundVccRequest;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import com.masonx.virtualaccount.vcc.dto.VccResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


@Service
public class VirtualCardService {

    private static final String VA_BIN = "999999";

    private final VirtualCardRepository virtualCardRepo;
    private final LedgerAccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final SnowflakeIdGenerator  idGen;
    private final VccFundingPostingRule fundingPostingRule;
    private final VccCloseSweepPostingRule closeSweepPostingRule;

    public VirtualCardService(VirtualCardRepository virtualCardRepo,
                               LedgerAccountRepository accountRepo,
                               LedgerFacade ledger,
                               SnowflakeIdGenerator idGen,
                               VccFundingPostingRule fundingPostingRule,
                               VccCloseSweepPostingRule closeSweepPostingRule) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.idGen           = idGen;
        this.fundingPostingRule = fundingPostingRule;
        this.closeSweepPostingRule = closeSweepPostingRule;
    }

    /**
     * Creates a VA-issued VCC backed by a new PREPAID_CARD account (initial balance = 0).
     * The test PAN is returned once in {@link CreateVccResponse} and never stored.
     */
    @Transactional
    public CreateVccResponse createCard(CreateVccRequest req) {
        // Verify the owner wallet account exists and belongs to this merchant.
        LedgerAccount ownerAccount = accountRepo.findById(req.ownerAccountId())
                .filter(a -> req.merchantId().equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "WALLET account not found or does not belong to merchant: " + req.ownerAccountId()));

        // Create the ring-fenced PREPAID_CARD account for available funds.
        String vccAccountId = idGen.generate(MasonXIdPrefix.VCC_ACCOUNT.prefix());
        LedgerAccount vccAccount = new LedgerAccount(
                vccAccountId,
                ownerAccount.mode(),
                LedgerAccountRole.TENANT,
                ownerAccount.orgId(),
                req.merchantId(),
                null,
                LedgerAccountType.PREPAID_CARD,
                req.currency(),
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE);
        accountRepo.save(vccAccount);

        // Create the paired hold account for authorized-but-unsettled funds.
        String holdAccountId = idGen.generate(MasonXIdPrefix.VCC_ACCOUNT.prefix());
        LedgerAccount holdAccount = new LedgerAccount(
                holdAccountId,
                ownerAccount.mode(),
                LedgerAccountRole.TENANT,
                ownerAccount.orgId(),
                req.merchantId(),
                null,
                LedgerAccountType.PREPAID_CARD_HOLD,
                req.currency(),
                AssetClass.FIAT,
                2,
                NormalBalance.DEBIT,
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE);
        accountRepo.save(holdAccount);

        // Generate simulator test PAN: BIN 999999 + 10 random digits.
        String testPan   = VA_BIN + randomPanSuffix();
        String maskedPan = VA_BIN + "****" + testPan.substring(testPan.length() - 4);
        LocalDate expiry = req.expiry() != null ? req.expiry() : LocalDate.now().plusYears(1);

        String cardId = idGen.generate(MasonXIdPrefix.VIRTUAL_CARD.prefix());
        VirtualCard card = new VirtualCard(
                cardId,
                maskedPan,
                VA_BIN,
                vccAccountId,
                holdAccountId,
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
                                .map(LedgerAccount::merchantId).orElse(null)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card not found or not owned by merchant: " + cardId));

        if (card.status() != VirtualCardStatus.ACTIVE) {
            throw new IllegalStateException("Cannot fund card in status: " + card.status());
        }

        LedgerAccount ownerAcct = accountRepo.findById(card.ownerAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Owner account not found for card: " + cardId));
        String eventId = fundEventId(cardId, req.idempotencyKey());
        ledger.postAllIfNew(
                fundingPostingRule.build(
                        new VccFundingPostingRule.FundingEvent(card, ownerAcct, req.amount(), eventId)),
                eventId,
                "vcc-card-fund");

        return getCard(cardId);
    }

    public VccResponse getCard(String cardId) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
        LedgerAccount account = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));
        LedgerAccount holdAccount = findHoldAccount(card);
        return toResponse(card, account, holdAccount);
    }

    public PagedResult<VccResponse> listCards(String merchantId, int page, int size) {
        long total = virtualCardRepo.countByMerchantId(merchantId);
        List<VccResponse> content = virtualCardRepo.findByMerchantId(merchantId, page, size).stream()
                .map(card -> {
                    LedgerAccount acct = accountRepo.findById(card.vccAccountId()).orElse(null);
                    LedgerAccount holdAcct = findHoldAccount(card);
                    return toResponse(card, acct, holdAcct);
                })
                .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedResult<>(content, page, size, total, totalPages);
    }

    /**
     * Closes the card: sweeps remaining balance back to the owner WALLET account,
     * then marks the card CLOSED and the PREPAID_CARD account CLOSED.
     */
    @Transactional
    public void closeCard(String cardId, String merchantId) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));

        LedgerAccount vccAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));
        LedgerAccount holdAccount = findHoldAccountForUpdate(card);

        // Validate ownership via the owner account's merchantId.
        LedgerAccount ownerAccount = accountRepo.findById(card.ownerAccountId())
                .filter(a -> merchantId.equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card does not belong to merchant: " + merchantId));

        if (holdAccount != null && holdAccount.balance().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot close card with open authorization hold: " + cardId);
        }

        BigDecimal remaining = vccAccount.balance();
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            for (var command : closeSweepPostingRule.build(
                    new VccCloseSweepPostingRule.CloseSweepEvent(card, ownerAccount, remaining))) {
                ledger.postDirect(command);
            }
        }

        virtualCardRepo.updateStatus(cardId, VirtualCardStatus.CLOSED);
        accountRepo.updateStatus(vccAccount.ledgerAccountId(), LedgerAccountStatus.CLOSED);
        if (holdAccount != null) {
            accountRepo.updateStatus(holdAccount.ledgerAccountId(), LedgerAccountStatus.CLOSED);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LedgerAccount findHoldAccount(VirtualCard card) {
        if (card.holdAccountId() == null) {
            return null;
        }
        return accountRepo.findById(card.holdAccountId()).orElse(null);
    }

    private LedgerAccount findHoldAccountForUpdate(VirtualCard card) {
        if (card.holdAccountId() == null) {
            return null;
        }
        return accountRepo.findByIdForUpdate(card.holdAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));
    }

    private static VccResponse toResponse(VirtualCard card, LedgerAccount account, LedgerAccount holdAccount) {
        BigDecimal balance  = account != null ? account.balance()         : BigDecimal.ZERO;
        BigDecimal frozen   = holdAccount != null ? holdAccount.balance() : BigDecimal.ZERO;
        BigDecimal avail    = balance;
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

    private static String fundEventId(String cardId, String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((cardId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
            return "vcc_fund_" + HexFormat.of().formatHex(hash).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
