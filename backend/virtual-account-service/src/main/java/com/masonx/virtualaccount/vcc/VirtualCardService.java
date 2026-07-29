package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.CardCreateRequestRepository;
import com.masonx.virtualaccount.domain.CardCreateRequestRepository.CardCreateRequest;
import com.masonx.virtualaccount.domain.CardIssuerReconciliationRepository;
import com.masonx.virtualaccount.domain.CardIssuerReconciliationRepository.CardIssuerReconciliationTask;
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
import com.masonx.virtualaccount.fee.AssessPrepaidFeeCommand;
import com.masonx.virtualaccount.fee.PrepaidFeeAssessmentService;
import com.masonx.virtualaccount.fee.PrepaidFeePostingService;
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
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;


@Service
public class VirtualCardService {

    private final VirtualCardRepository virtualCardRepo;
    private final CardProgramRepository cardProgramRepo;
    private final CardControlProfileRepository cardControlProfileRepo;
    private final CardCreateRequestRepository cardCreateRequestRepo;
    private final CardIssuerReconciliationRepository cardIssuerReconciliationRepo;
    private final CardholderRepository cardholderRepo;
    private final IssuerPartnerRepository issuerPartnerRepo;
    private final LedgerAccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final IssuerCardProviderDispatcher issuerCards;
    private final SnowflakeIdGenerator  idGen;
    private final VccFundingPostingRule fundingPostingRule;
    private final VccCloseSweepPostingRule closeSweepPostingRule;
    private final VccWithdrawPostingRule withdrawPostingRule;
    private final PrepaidFeeAssessmentService prepaidFeeAssessmentService;
    private final PrepaidFeePostingService prepaidFeePostingService;
    private final TransactionOperations transactionOperations;

    public VirtualCardService(VirtualCardRepository virtualCardRepo,
                               CardProgramRepository cardProgramRepo,
                               CardControlProfileRepository cardControlProfileRepo,
                               CardCreateRequestRepository cardCreateRequestRepo,
                               CardIssuerReconciliationRepository cardIssuerReconciliationRepo,
                               CardholderRepository cardholderRepo,
                               IssuerPartnerRepository issuerPartnerRepo,
                               LedgerAccountRepository accountRepo,
                               LedgerFacade ledger,
                               IssuerCardProviderDispatcher issuerCards,
                               SnowflakeIdGenerator idGen,
                               VccFundingPostingRule fundingPostingRule,
                               VccCloseSweepPostingRule closeSweepPostingRule,
                               VccWithdrawPostingRule withdrawPostingRule,
                               PrepaidFeeAssessmentService prepaidFeeAssessmentService,
                               PrepaidFeePostingService prepaidFeePostingService,
                               TransactionOperations transactionOperations) {
        this.virtualCardRepo = virtualCardRepo;
        this.cardProgramRepo = cardProgramRepo;
        this.cardControlProfileRepo = cardControlProfileRepo;
        this.cardCreateRequestRepo = cardCreateRequestRepo;
        this.cardIssuerReconciliationRepo = cardIssuerReconciliationRepo;
        this.cardholderRepo = cardholderRepo;
        this.issuerPartnerRepo = issuerPartnerRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.issuerCards     = issuerCards;
        this.idGen           = idGen;
        this.fundingPostingRule = fundingPostingRule;
        this.closeSweepPostingRule = closeSweepPostingRule;
        this.withdrawPostingRule = withdrawPostingRule;
        this.prepaidFeeAssessmentService = prepaidFeeAssessmentService;
        this.prepaidFeePostingService = prepaidFeePostingService;
        this.transactionOperations = transactionOperations;
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
        assertRequestedMode(ownerAccount, req.mode());
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

        String clientKey = requireCreateIdempotencyKey(req.idempotencyKey());
        CardCreateRequest createRequest = reserveCreateRequest(req.merchantId(), ownerAccount.mode(), clientKey);
        if ("SUCCEEDED".equals(createRequest.status())) {
            return toCreateResponse(getCard(createRequest.cardId(), req.merchantId(), ownerAccount.mode()));
        }

        LocalDate expiry = req.expiry() != null ? req.expiry() : LocalDate.now().plusYears(1);
        CreateIssuerCardResult issuerCard;
        try {
            issuerCard = issuerCards.require(issuerPartner.adapterType())
                    .createCard(new CreateIssuerCardCommand(
                            issuerCreateIdempotencyKey(req.merchantId(), ownerAccount.mode(), clientKey),
                            req.merchantId(),
                            ownerAccount.mode(),
                            program.programId(),
                            program.issuerPartnerId(),
                            issuerPartner.adapterType(),
                            cardholder.cardholderId(),
                            req.currency(),
                            expiry));
        } catch (RuntimeException ex) {
            cardCreateRequestRepo.markFailed(req.merchantId(), ownerAccount.mode(), clientKey, ex.getMessage());
            throw ex;
        }

        try {
            return transactionOperations.execute(status -> {
                LedgerAccount vccAccount = new LedgerAccount(
                        createRequest.vccAccountId(),
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
                accountRepo.saveIfAbsent(vccAccount);

                LedgerAccount holdAccount = new LedgerAccount(
                        createRequest.holdAccountId(),
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
                accountRepo.saveIfAbsent(holdAccount);

                Instant now = Instant.now();
                VirtualCard card = new VirtualCard(
                        createRequest.cardId(),
                        issuerCard.cardTokenId(),
                        issuerCard.maskedPan(),
                        issuerCard.bin(),
                        createRequest.vccAccountId(),
                        createRequest.holdAccountId(),
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
                        now,
                        now);
                virtualCardRepo.saveIfAbsent(card);
                assessAndPostCardCreateFee(req, ownerAccount, program, cardholder, issuerCard, createRequest.cardId(), now);
                cardCreateRequestRepo.markSucceeded(req.merchantId(), ownerAccount.mode(), clientKey);

                return new CreateVccResponse(
                        createRequest.cardId(), issuerCard.cardTokenId(), program.programId(), program.issuerPartnerId(),
                        cardholder.cardholderId(), issuerCard.externalIssuerCardId(), issuerCard.externalCardToken(),
                        issuerCard.oneTimeTestPan(), issuerCard.maskedPan(), issuerCard.bin(),
                        req.currency(), issuerCard.expiry() != null ? issuerCard.expiry().toString() : null);
            });
        } catch (RuntimeException ex) {
            cardCreateRequestRepo.markFailed(req.merchantId(), ownerAccount.mode(), clientKey, ex.getMessage());
            throw ex;
        }
    }

    private void assessAndPostCardCreateFee(CreateVccRequest req,
                                            LedgerAccount ownerAccount,
                                            CardProgram program,
                                            Cardholder cardholder,
                                            CreateIssuerCardResult issuerCard,
                                            String cardId,
                                            Instant occurredAt) {
        prepaidFeeAssessmentService.assessAndPersist(new AssessPrepaidFeeCommand(
                req.merchantId(),
                ownerAccount.mode(),
                "CARD_CREATE",
                cardId,
                program.programId(),
                cardId,
                issuerCard.bin(),
                "VIRTUAL",
                Map.of(
                        "cardCurrency", req.currency(),
                        "accountCurrency", ownerAccount.asset(),
                        "cardholderId", cardholder.cardholderId(),
                        "issuerPartnerId", program.issuerPartnerId(),
                        "fundingWalletId", ownerAccount.ledgerAccountId()),
                occurredAt
        )).ifPresent(snapshot -> prepaidFeePostingService.postAssessmentFeesFromWallet(
                snapshot, ownerAccount.ledgerAccountId()));
    }

    /**
     * Transfers {@code amount} from the card's linked WALLET account to its PREPAID_CARD account.
     * Uses the double-entry ledger (DR PREPAID_CARD / CR WALLET).
     */
    @Transactional
    public VccResponse fundCard(String cardId, FundVccRequest req) {
        OwnedCard owned = requireOwnedCard(cardId, req.merchantId(), req.mode());
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

        return toResponse(card, accountRepo.findById(card.vccAccountId()).orElse(null), findHoldAccount(card));
    }

    @Transactional
    public VccResponse withdrawCard(String cardId, WithdrawVccRequest req) {
        OwnedCard owned = requireOwnedCard(cardId, req.merchantId(), req.mode());
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
        return toResponse(card, accountRepo.findById(card.vccAccountId()).orElse(null), findHoldAccount(card));
    }

    public VccResponse getCard(String cardId, String merchantId, Mode mode) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId, mode != null ? mode.name() : null);
        VirtualCard card = owned.card();
        LedgerAccount account = accountRepo.findById(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + cardId));
        LedgerAccount holdAccount = findHoldAccount(card);
        return toResponse(card, account, holdAccount);
    }

    public PagedResult<VccResponse> listCards(String merchantId, Mode mode, int page, int size) {
        long total = virtualCardRepo.countByMerchantIdAndMode(merchantId, mode);
        List<VccResponse> content = virtualCardRepo.findByMerchantIdAndMode(merchantId, mode, page, size).stream()
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
        closeCard(cardId, merchantId, null);
    }

    @Transactional
    public void closeCard(String cardId, String merchantId, String mode) {
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
        assertRequestedMode(ownerAccount, mode);

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
        return lockCard(cardId, merchantId, null, reason);
    }

    public VccResponse lockCard(String cardId, String merchantId, String mode, String reason) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId, mode);
        VirtualCard card = owned.card();
        if (card.status() == VirtualCardStatus.LOCKED) {
            return toResponse(card, accountRepo.findById(card.vccAccountId()).orElse(null), findHoldAccount(card));
        }
        if (card.status() != VirtualCardStatus.ACTIVE) {
            throw new IllegalStateException("Cannot lock card in status: " + card.status());
        }

        IssuerPartner issuerPartner = requireIssuerPartner(card, owned.ownerAccount());
        String idempotencyKey = "issuer_card_lock:" + cardId;
        issuerCards.require(issuerPartner.adapterType())
                .lockCard(requireExternalIssuerCardId(card), reason, idempotencyKey);
        commitIssuerLifecycleMutation(owned, issuerPartner, "LOCK", VirtualCardStatus.LOCKED, reason, idempotencyKey,
                () -> virtualCardRepo.updateStatus(cardId, VirtualCardStatus.LOCKED));
        return getCard(cardId, merchantId, owned.ownerAccount().mode());
    }

    public VccResponse unlockCard(String cardId, String merchantId) {
        return unlockCard(cardId, merchantId, null);
    }

    public VccResponse unlockCard(String cardId, String merchantId, String mode) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId, mode);
        VirtualCard card = owned.card();
        if (card.status() == VirtualCardStatus.ACTIVE) {
            return toResponse(card, accountRepo.findById(card.vccAccountId()).orElse(null), findHoldAccount(card));
        }
        if (card.status() != VirtualCardStatus.LOCKED) {
            throw new IllegalStateException("Cannot unlock card in status: " + card.status());
        }

        IssuerPartner issuerPartner = requireIssuerPartner(card, owned.ownerAccount());
        String idempotencyKey = "issuer_card_unlock:" + cardId;
        issuerCards.require(issuerPartner.adapterType())
                .unlockCard(requireExternalIssuerCardId(card), idempotencyKey);
        commitIssuerLifecycleMutation(owned, issuerPartner, "UNLOCK", VirtualCardStatus.ACTIVE, null, idempotencyKey,
                () -> virtualCardRepo.updateStatus(cardId, VirtualCardStatus.ACTIVE));
        return getCard(cardId, merchantId, owned.ownerAccount().mode());
    }

    public VccResponse terminateCard(String cardId, String merchantId, String reason) {
        return terminateCard(cardId, merchantId, null, reason);
    }

    public VccResponse terminateCard(String cardId, String merchantId, String mode, String reason) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId, mode);
        VirtualCard card = owned.card();
        if (card.status() == VirtualCardStatus.TERMINATED) {
            return toResponse(card, accountRepo.findById(card.vccAccountId()).orElse(null), findHoldAccount(card));
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

        IssuerPartner issuerPartner = requireIssuerPartner(card, owned.ownerAccount());
        String idempotencyKey = "issuer_card_terminate:" + cardId;
        issuerCards.require(issuerPartner.adapterType())
                .terminateCard(requireExternalIssuerCardId(card), reason, idempotencyKey);
        commitIssuerLifecycleMutation(owned, issuerPartner, "TERMINATE", VirtualCardStatus.TERMINATED, reason,
                idempotencyKey, () -> {
                    virtualCardRepo.updateStatus(cardId, VirtualCardStatus.TERMINATED);
                    accountRepo.updateStatus(card.vccAccountId(), LedgerAccountStatus.CLOSED);
                    if (holdAccount != null) {
                        accountRepo.updateStatus(holdAccount.ledgerAccountId(), LedgerAccountStatus.CLOSED);
                    }
                });
        return getCard(cardId, merchantId, owned.ownerAccount().mode());
    }

    public CardControlResponse updateCardControls(String cardId, UpdateCardControlsRequest req) {
        OwnedCard owned = requireOwnedCard(cardId, req.merchantId(), req.mode());
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
        return getCardControls(cardId, merchantId, null);
    }

    public CardControlResponse getCardControls(String cardId, String merchantId, Mode mode) {
        OwnedCard owned = requireOwnedCard(cardId, merchantId, mode != null ? mode.name() : null);
        return cardControlProfileRepo
                .findByCardIdForMerchant(cardId, owned.ownerAccount().merchantId(), owned.ownerAccount().mode())
                .map(profile -> new CardControlResponse(
                        profile.cardId(), profile.merchantId(), profile.mode().name(), profile.controlsJson()))
                .orElseGet(() -> new CardControlResponse(
                        cardId, owned.ownerAccount().merchantId(), owned.ownerAccount().mode().name(), "{}"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OwnedCard requireOwnedCard(String cardId, String merchantId) {
        return requireOwnedCard(cardId, merchantId, null);
    }

    private OwnedCard requireOwnedCard(String cardId, String merchantId, String mode) {
        VirtualCard card = virtualCardRepo.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
        LedgerAccount ownerAccount = accountRepo.findById(card.ownerAccountId())
                .filter(a -> merchantId.equals(a.merchantId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Card not found or not owned by merchant: " + cardId));
        assertRequestedMode(ownerAccount, mode);
        return new OwnedCard(card, ownerAccount);
    }

    private CardCreateRequest reserveCreateRequest(String merchantId, Mode mode, String idempotencyKey) {
        CardCreateRequest request = new CardCreateRequest(
                merchantId,
                mode,
                idempotencyKey,
                idGen.generate(MasonXIdPrefix.VIRTUAL_CARD.prefix()),
                idGen.generate(MasonXIdPrefix.VCC_ACCOUNT.prefix()),
                idGen.generate(MasonXIdPrefix.VCC_ACCOUNT.prefix()),
                "PROCESSING",
                null);
        cardCreateRequestRepo.insertIfAbsent(request);
        return cardCreateRequestRepo.find(merchantId, mode, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Card create request reservation failed"));
    }

    private static String requireCreateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return idempotencyKey.trim();
    }

    private void commitIssuerLifecycleMutation(OwnedCard owned,
                                               IssuerPartner issuerPartner,
                                               String action,
                                               VirtualCardStatus targetStatus,
                                               String reason,
                                               String idempotencyKey,
                                               Runnable localMutation) {
        try {
            transactionOperations.execute(status -> {
                localMutation.run();
                return null;
            });
        } catch (RuntimeException ex) {
            VirtualCard card = owned.card();
            cardIssuerReconciliationRepo.upsertOpen(new CardIssuerReconciliationTask(
                    lifecycleReconciliationTaskId(card.cardId(), action, idempotencyKey),
                    owned.ownerAccount().merchantId(),
                    owned.ownerAccount().mode(),
                    card.cardId(),
                    issuerPartner.issuerPartnerId(),
                    requireExternalIssuerCardId(card),
                    action,
                    targetStatus.name(),
                    reason,
                    idempotencyKey,
                    ex.getMessage()));
            throw ex;
        }
    }

    private void assertRequestedMode(LedgerAccount ownerAccount, String requestedMode) {
        if (requestedMode == null || requestedMode.isBlank()) {
            return;
        }
        Mode mode = Mode.valueOf(requestedMode.toUpperCase());
        if (ownerAccount.mode() != mode) {
            throw new IllegalArgumentException(
                    "Card not found or not owned by merchant/mode: " + ownerAccount.merchantId());
        }
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

    private static CreateVccResponse toCreateResponse(VccResponse response) {
        return new CreateVccResponse(
                response.cardId(),
                response.cardTokenId(),
                response.programId(),
                response.issuerPartnerId(),
                response.cardholderId(),
                response.externalIssuerCardId(),
                response.externalCardToken(),
                null,
                response.maskedPan(),
                response.bin(),
                response.currency(),
                response.expiry());
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

    private static String issuerCreateIdempotencyKey(String merchantId, Mode mode, String idempotencyKey) {
        return "issuer_card_create:" + stableHash(merchantId + ":" + mode.name() + ":" + idempotencyKey, 40);
    }

    private static String lifecycleReconciliationTaskId(String cardId, String action, String idempotencyKey) {
        return "cir_" + stableHash(cardId + ":" + action + ":" + idempotencyKey, 40);
    }

    private static String stableHash(String input, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private record OwnedCard(VirtualCard card, LedgerAccount ownerAccount) {
    }
}
