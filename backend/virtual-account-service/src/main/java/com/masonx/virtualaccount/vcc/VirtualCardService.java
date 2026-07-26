package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.CardProgramRepository;
import com.masonx.virtualaccount.domain.CardControlProfileRepository;
import com.masonx.virtualaccount.domain.CardholderRepository;
import com.masonx.virtualaccount.domain.IssuerPartnerRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.VccCloseSweepPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.VccFundingPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.VccWithdrawPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.CardControlProfile;
import com.masonx.virtualaccount.domain.po.CardProgram;
import com.masonx.virtualaccount.domain.po.Cardholder;
import com.masonx.virtualaccount.domain.po.IssuerPartner;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.issuer.CreateIssuerCardCommand;
import com.masonx.virtualaccount.issuer.CreateIssuerCardResult;
import com.masonx.virtualaccount.issuer.IssuerCardProviderDispatcher;
import com.masonx.virtualaccount.vcc.dto.CardControlResponse;
import com.masonx.virtualaccount.vcc.dto.CreateVccRequest;
import com.masonx.virtualaccount.vcc.dto.CreateVccResponse;
import com.masonx.virtualaccount.vcc.dto.FundVccRequest;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import com.masonx.virtualaccount.vcc.dto.UpdateCardControlsRequest;
import com.masonx.virtualaccount.vcc.dto.VccResponse;
import com.masonx.virtualaccount.vcc.dto.WithdrawVccRequest;
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


@Service
public class VirtualCardService {

    private final VirtualCardRepository virtualCardRepo;
    private final CardProgramRepository cardProgramRepo;
    private final CardControlProfileRepository cardControlProfileRepo;
    private final CardholderRepository cardholderRepo;
    private final IssuerPartnerRepository issuerPartnerRepo;
    private final LedgerAccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final IssuerCardProviderDispatcher issuerCards;
    private final SnowflakeIdGenerator  idGen;
    private final VccFundingPostingRule fundingPostingRule;
    private final VccCloseSweepPostingRule closeSweepPostingRule;
    private final VccWithdrawPostingRule withdrawPostingRule;

    public VirtualCardService(VirtualCardRepository virtualCardRepo,
                               CardProgramRepository cardProgramRepo,
                               CardControlProfileRepository cardControlProfileRepo,
                               CardholderRepository cardholderRepo,
                               IssuerPartnerRepository issuerPartnerRepo,
                               LedgerAccountRepository accountRepo,
                               LedgerFacade ledger,
                               IssuerCardProviderDispatcher issuerCards,
                               SnowflakeIdGenerator idGen,
                               VccFundingPostingRule fundingPostingRule,
                               VccCloseSweepPostingRule closeSweepPostingRule,
                               VccWithdrawPostingRule withdrawPostingRule) {
        this.virtualCardRepo = virtualCardRepo;
        this.cardProgramRepo = cardProgramRepo;
        this.cardControlProfileRepo = cardControlProfileRepo;
        this.cardholderRepo = cardholderRepo;
        this.issuerPartnerRepo = issuerPartnerRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.issuerCards     = issuerCards;
        this.idGen           = idGen;
        this.fundingPostingRule = fundingPostingRule;
        this.closeSweepPostingRule = closeSweepPostingRule;
        this.withdrawPostingRule = withdrawPostingRule;
    }

    /**
     * Creates a VA-issued VCC backed by a new PREPAID_CARD account (initial balance = 0).
     * The test PAN is returned once in {@link CreateVccResponse} and never stored.
     */
    public CreateVccResponse createCard(CreateVccRequest req) {
        // Verify the owner wallet account exists and belongs to this merchant.
        LedgerAccount ownerAccount = accountRepo.findById(req.ownerAccountId())
                .filter(a -> req.merchantId().equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "WALLET account not found or does not belong to merchant: " + req.ownerAccountId()));
        if (ownerAccount.ledgerAccountType() != LedgerAccountType.WALLET) {
            throw new IllegalArgumentException("Owner account must be a WALLET account: " + req.ownerAccountId());
        }

        CardProgram program = cardProgramRepo.findByIdForMerchant(req.programId(), req.merchantId(), ownerAccount.mode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card program not found or does not belong to merchant/mode: " + req.programId()));
        if (program.status() != CardProgramStatus.ACTIVE) {
            throw new IllegalStateException("Cannot create card for program in status: " + program.status());
        }
        if (!program.currency().equalsIgnoreCase(req.currency())) {
            throw new IllegalArgumentException("Card currency must match card program currency: " + program.currency());
        }
        IssuerPartner issuerPartner = issuerPartnerRepo
                .findByIdForMerchant(program.issuerPartnerId(), req.merchantId(), ownerAccount.mode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Issuer partner not found or does not belong to merchant/mode: " + program.issuerPartnerId()));
        if (issuerPartner.status() != IssuerPartnerStatus.ACTIVE) {
            throw new IllegalStateException("Cannot create card for issuer partner in status: "
                    + issuerPartner.status());
        }

        Cardholder cardholder = cardholderRepo
                .findByIdForMerchant(req.cardholderId(), req.merchantId(), ownerAccount.mode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cardholder not found or does not belong to merchant/mode: " + req.cardholderId()));
        if (cardholder.kycStatus() != CardholderKycStatus.ACTIVE) {
            throw new IllegalStateException("Cannot create card for cardholder in KYC status: "
                    + cardholder.kycStatus());
        }

        LocalDate expiry = req.expiry() != null ? req.expiry() : LocalDate.now().plusYears(1);
        String cardId = idGen.generate(MasonXIdPrefix.VIRTUAL_CARD.prefix());
        CreateIssuerCardResult issuerCard = issuerCards.require(issuerPartner.adapterType())
                .createCard(new CreateIssuerCardCommand(
                        issuerCreateIdempotencyKey(cardId),
                        req.merchantId(),
                        ownerAccount.mode(),
                        program.programId(),
                        program.issuerPartnerId(),
                        issuerPartner.adapterType(),
                        cardholder.cardholderId(),
                        req.currency(),
                        expiry));

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
                NormalBalance.CREDIT,
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
                NormalBalance.CREDIT,
                BigDecimal.ZERO,
                LedgerAccountStatus.ACTIVE);
        accountRepo.save(holdAccount);

        VirtualCard card = new VirtualCard(
                cardId,
                issuerCard.cardTokenId(),
                issuerCard.maskedPan(),
                issuerCard.bin(),
                vccAccountId,
                holdAccountId,
                req.ownerAccountId(),
                program.programId(),
                program.issuerPartnerId(),
                cardholder.cardholderId(),
                issuerCard.externalIssuerCardId(),
                issuerCard.externalCardToken(),
                VirtualCardStatus.ACTIVE,
                req.spendingLimit(),
                req.currency(),
                issuerCard.expiry(),
                Instant.now(),
                Instant.now());
        virtualCardRepo.save(card);

        return new CreateVccResponse(
                cardId, issuerCard.cardTokenId(), program.programId(), program.issuerPartnerId(),
                cardholder.cardholderId(), issuerCard.externalIssuerCardId(), issuerCard.externalCardToken(),
                issuerCard.oneTimeTestPan(), issuerCard.maskedPan(), issuerCard.bin(),
                req.currency(), issuerCard.expiry() != null ? issuerCard.expiry().toString() : null);
    }

    /**
     * Transfers {@code amount} from the card's linked WALLET account to its PREPAID_CARD account.
     * Uses the double-entry ledger (DR PREPAID_CARD / CR WALLET).
     */
    @Transactional
    public VccResponse fundCard(String cardId, FundVccRequest req) {
        OwnedCard owned = requireOwnedCard(cardId, req.merchantId());
        VirtualCard card = owned.card();

        if (card.status() != VirtualCardStatus.ACTIVE) {
            throw new IllegalStateException("Cannot fund card in status: " + card.status());
        }

        String eventId = fundEventId(cardId, req.idempotencyKey());
        ledger.postAllIfNew(
                fundingPostingRule.build(
                        new VccFundingPostingRule.FundingEvent(card, owned.ownerAccount(), req.amount(), eventId)),
                eventId,
                "vcc-card-fund");

        return getCard(cardId);
    }

    @Transactional
    public VccResponse withdrawCard(String cardId, WithdrawVccRequest req) {
        OwnedCard owned = requireOwnedCard(cardId, req.merchantId());
        VirtualCard card = owned.card();
        if (card.status() != VirtualCardStatus.ACTIVE && card.status() != VirtualCardStatus.LOCKED) {
            throw new IllegalStateException("Cannot withdraw card funds in status: " + card.status());
        }

        LedgerAccount cardAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));
        if (cardAccount.balance().compareTo(req.amount()) < 0) {
            throw new IllegalStateException("Insufficient available card balance");
        }

        String eventId = withdrawEventId(cardId, req.idempotencyKey());
        ledger.postAllIfNew(
                withdrawPostingRule.build(
                        new VccWithdrawPostingRule.WithdrawEvent(card, owned.ownerAccount(), req.amount(), eventId)),
                eventId,
                "vcc-card-withdraw");
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
            String eventId = closeEventId(cardId);
            ledger.postAllIfNew(
                    closeSweepPostingRule.build(
                            new VccCloseSweepPostingRule.CloseSweepEvent(card, ownerAccount, remaining, eventId)),
                    eventId,
                    "vcc-card-close");
        }

        virtualCardRepo.updateStatus(cardId, VirtualCardStatus.CLOSED);
        accountRepo.updateStatus(vccAccount.ledgerAccountId(), LedgerAccountStatus.CLOSED);
        if (holdAccount != null) {
            accountRepo.updateStatus(holdAccount.ledgerAccountId(), LedgerAccountStatus.CLOSED);
        }
    }

    public VccResponse lockCard(String cardId, String merchantId, String reason) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId);
        VirtualCard card = owned.card();
        if (card.status() == VirtualCardStatus.LOCKED) {
            return getCard(cardId);
        }
        if (card.status() != VirtualCardStatus.ACTIVE) {
            throw new IllegalStateException("Cannot lock card in status: " + card.status());
        }

        issuerCards.require(requireIssuerPartner(card, owned.ownerAccount()).adapterType())
                .lockCard(requireExternalIssuerCardId(card), reason, "issuer_card_lock:" + cardId);
        virtualCardRepo.updateStatus(cardId, VirtualCardStatus.LOCKED);
        return getCard(cardId);
    }

    public VccResponse unlockCard(String cardId, String merchantId) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId);
        VirtualCard card = owned.card();
        if (card.status() == VirtualCardStatus.ACTIVE) {
            return getCard(cardId);
        }
        if (card.status() != VirtualCardStatus.LOCKED) {
            throw new IllegalStateException("Cannot unlock card in status: " + card.status());
        }

        issuerCards.require(requireIssuerPartner(card, owned.ownerAccount()).adapterType())
                .unlockCard(requireExternalIssuerCardId(card), "issuer_card_unlock:" + cardId);
        virtualCardRepo.updateStatus(cardId, VirtualCardStatus.ACTIVE);
        return getCard(cardId);
    }

    public VccResponse terminateCard(String cardId, String merchantId, String reason) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId);
        VirtualCard card = owned.card();
        if (card.status() == VirtualCardStatus.TERMINATED) {
            return getCard(cardId);
        }
        if (card.status() == VirtualCardStatus.CLOSED) {
            throw new IllegalStateException("Cannot terminate already closed card: " + cardId);
        }

        LedgerAccount cardAccount = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));
        LedgerAccount holdAccount = findHoldAccount(card);
        if (cardAccount.balance().compareTo(BigDecimal.ZERO) > 0
                || (holdAccount != null && holdAccount.balance().compareTo(BigDecimal.ZERO) > 0)) {
            throw new IllegalStateException("Cannot terminate card with remaining balance or open hold: " + cardId);
        }

        issuerCards.require(requireIssuerPartner(card, owned.ownerAccount()).adapterType())
                .terminateCard(requireExternalIssuerCardId(card), reason, "issuer_card_terminate:" + cardId);
        virtualCardRepo.updateStatus(cardId, VirtualCardStatus.TERMINATED);
        accountRepo.updateStatus(card.vccAccountId(), LedgerAccountStatus.CLOSED);
        if (holdAccount != null) {
            accountRepo.updateStatus(holdAccount.ledgerAccountId(), LedgerAccountStatus.CLOSED);
        }
        return getCard(cardId);
    }

    public CardControlResponse updateCardControls(String cardId, UpdateCardControlsRequest req) {
        OwnedCard owned = requireOwnedCard(cardId, req.merchantId());
        CardControlProfile profile = new CardControlProfile(
                cardId,
                owned.ownerAccount().merchantId(),
                owned.ownerAccount().mode(),
                req.controlsJson() != null ? req.controlsJson() : "{}",
                Instant.now(),
                Instant.now());
        cardControlProfileRepo.upsert(profile);
        return new CardControlResponse(
                cardId,
                owned.ownerAccount().merchantId(),
                owned.ownerAccount().mode().name(),
                profile.controlsJson());
    }

    public CardControlResponse getCardControls(String cardId, String merchantId) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId);
        return cardControlProfileRepo
                .findByCardIdForMerchant(cardId, owned.ownerAccount().merchantId(), owned.ownerAccount().mode())
                .map(profile -> new CardControlResponse(
                        profile.cardId(), profile.merchantId(), profile.mode().name(), profile.controlsJson()))
                .orElseGet(() -> new CardControlResponse(
                        cardId, owned.ownerAccount().merchantId(), owned.ownerAccount().mode().name(), "{}"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OwnedCard requireOwnedCard(String cardId, String merchantId) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
        LedgerAccount ownerAccount = accountRepo.findById(card.ownerAccountId())
                .filter(a -> merchantId.equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card not found or not owned by merchant: " + cardId));
        return new OwnedCard(card, ownerAccount);
    }

    private IssuerPartner requireIssuerPartner(VirtualCard card, LedgerAccount ownerAccount) {
        if (card.issuerPartnerId() == null || card.issuerPartnerId().isBlank()) {
            throw new IllegalStateException("Card has no issuer partner reference: " + card.cardId());
        }
        return issuerPartnerRepo
                .findByIdForMerchant(card.issuerPartnerId(), ownerAccount.merchantId(), ownerAccount.mode())
                .orElseThrow(() -> new IllegalStateException(
                        "Issuer partner not found for card: " + card.cardId()));
    }

    private static String requireExternalIssuerCardId(VirtualCard card) {
        if (card.externalIssuerCardId() == null || card.externalIssuerCardId().isBlank()) {
            throw new IllegalStateException("Card has no external issuer card reference: " + card.cardId());
        }
        return card.externalIssuerCardId();
    }

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
                card.cardId(), card.cardTokenId(),
                card.programId(), card.issuerPartnerId(), card.cardholderId(),
                card.externalIssuerCardId(), card.externalCardToken(),
                card.maskedPan(), card.bin(),
                card.status().name(),
                balance, frozen, avail,
                card.spendingLimit(),
                card.currency(),
                card.expiry() != null ? card.expiry().toString() : null);
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

    private static String closeEventId(String cardId) {
        return "vcc_close_" + cardId;
    }

    private static String withdrawEventId(String cardId, String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((cardId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
            return "vcc_withdraw_" + HexFormat.of().formatHex(hash).substring(0, 43);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static String issuerCreateIdempotencyKey(String cardId) {
        return "issuer_card_create:" + cardId;
    }

    private record OwnedCard(VirtualCard card, LedgerAccount ownerAccount) {
    }
}
