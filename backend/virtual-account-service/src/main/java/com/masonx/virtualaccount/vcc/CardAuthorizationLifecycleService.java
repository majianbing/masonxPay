package com.masonx.virtualaccount.vcc;

import com.masonx.virtualaccount.domain.CardAuthorizationRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.CardAuthHoldReleasePostingRule;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthReversalRequest;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthReversalResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class CardAuthorizationLifecycleService {

    public static final String RESULT_RELEASED = "RELEASED";
    public static final String RESULT_NOT_FOUND = "NOT_FOUND";
    public static final String RESULT_NOT_OPEN = "NOT_OPEN";
    public static final String REASON_AUTH_NOT_FOUND = "AUTH_NOT_FOUND";
    public static final String REASON_AUTH_NOT_OPEN = "AUTH_NOT_OPEN";
    public static final String REASON_CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String REASON_RELEASE_AMOUNT_EXCEEDS_HOLD = "RELEASE_AMOUNT_EXCEEDS_HOLD";

    private static final String RELEASE_REASON_REVERSAL = "REVERSAL";
    private static final String RELEASE_REASON_EXPIRY = "EXPIRY";

    private final CardAuthorizationRepository authorizationRepo;
    private final VirtualCardRepository virtualCardRepo;
    private final LedgerAccountRepository accountRepo;
    private final LedgerFacade ledger;
    private final CardAuthHoldReleasePostingRule holdReleasePostingRule;
    private final Clock clock;

    @Autowired
    public CardAuthorizationLifecycleService(CardAuthorizationRepository authorizationRepo,
                                             VirtualCardRepository virtualCardRepo,
                                             LedgerAccountRepository accountRepo,
                                             LedgerFacade ledger,
                                             CardAuthHoldReleasePostingRule holdReleasePostingRule) {
        this(authorizationRepo, virtualCardRepo, accountRepo, ledger, holdReleasePostingRule, Clock.systemUTC());
    }

    CardAuthorizationLifecycleService(CardAuthorizationRepository authorizationRepo,
                                      VirtualCardRepository virtualCardRepo,
                                      LedgerAccountRepository accountRepo,
                                      LedgerFacade ledger,
                                      CardAuthHoldReleasePostingRule holdReleasePostingRule,
                                      Clock clock) {
        this.authorizationRepo = authorizationRepo;
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo = accountRepo;
        this.ledger = ledger;
        this.holdReleasePostingRule = holdReleasePostingRule;
        this.clock = clock;
    }

    @Transactional
    public IssuerAuthReversalResponse reverse(String issuerId, IssuerAuthReversalRequest request) {
        CardAuthorization auth = authorizationRepo
                .findByIssuerIdAndAuthorizationIdForUpdate(issuerId, request.authorizationId())
                .orElse(null);
        if (auth == null) {
            return new IssuerAuthReversalResponse(RESULT_NOT_FOUND, REASON_AUTH_NOT_FOUND,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal releaseAmount = request.amount() != null ? request.amount() : remainingHold(auth);
        return release(issuerId, auth, request.reversalId(), releaseAmount, request.currency(), RELEASE_REASON_REVERSAL);
    }

    @Transactional
    public int expireStaleHolds(Duration holdTtl, int limit) {
        Instant cutoff = Instant.now(clock).minus(holdTtl);
        int released = 0;
        for (CardAuthorization auth : authorizationRepo.findAuthorizedBeforeForUpdate(cutoff, limit)) {
            if (remainingHold(auth).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            IssuerAuthReversalResponse response = release(
                    auth.issuerId(),
                    auth,
                    "expiry",
                    remainingHold(auth),
                    auth.currency(),
                    RELEASE_REASON_EXPIRY);
            if (RESULT_RELEASED.equals(response.result())) {
                released++;
            }
        }
        return released;
    }

    private IssuerAuthReversalResponse release(String issuerId,
                                               CardAuthorization auth,
                                               String releaseId,
                                               BigDecimal releaseAmount,
                                               String currency,
                                               String releaseReason) {
        BigDecimal remaining = remainingHold(auth);
        if (auth.status() != CardAuthorizationStatus.AUTHORIZED || auth.holdEventId() == null) {
            return new IssuerAuthReversalResponse(RESULT_NOT_OPEN, REASON_AUTH_NOT_OPEN,
                    BigDecimal.ZERO, remaining);
        }
        if (!auth.currency().equalsIgnoreCase(currency)) {
            return new IssuerAuthReversalResponse(RESULT_NOT_OPEN, REASON_CURRENCY_MISMATCH,
                    BigDecimal.ZERO, remaining);
        }
        if (releaseAmount.compareTo(BigDecimal.ZERO) <= 0 || releaseAmount.compareTo(remaining) > 0) {
            return new IssuerAuthReversalResponse(RESULT_NOT_OPEN, REASON_RELEASE_AMOUNT_EXCEEDS_HOLD,
                    BigDecimal.ZERO, remaining);
        }

        VirtualCard card = virtualCardRepo.findById(auth.cardId())
                .orElseThrow(() -> new IllegalStateException("Card not found for authorization: " + auth.authId()));
        LedgerAccount cardAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findByIdForUpdate(card.holdAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));

        String eventId = releaseEventId(issuerId, auth.authorizationId(), releaseId, releaseReason);
        boolean posted = ledger.postAllIfNew(
                holdReleasePostingRule.build(new CardAuthHoldReleasePostingRule.HoldReleaseEvent(
                        card,
                        cardAccount,
                        holdAccount,
                        auth.authorizationId(),
                        releaseAmount,
                        auth.currency(),
                        eventId,
                        "Card auth hold release " + auth.authorizationId())),
                eventId,
                "card-auth-hold-release");
        if (!posted) {
            return new IssuerAuthReversalResponse(RESULT_RELEASED, null, BigDecimal.ZERO, remaining);
        }

        BigDecimal releasedTotal = safeReleasedAmount(auth).add(releaseAmount);
        BigDecimal remainingAfter = auth.amount().subtract(releasedTotal);
        CardAuthorizationStatus nextStatus = remainingAfter.compareTo(BigDecimal.ZERO) == 0
                ? terminalStatus(releaseReason)
                : CardAuthorizationStatus.AUTHORIZED;
        authorizationRepo.recordHoldRelease(
                auth.authId(), releasedTotal, nextStatus, releaseReason, Instant.now(clock));
        return new IssuerAuthReversalResponse(RESULT_RELEASED, null, releaseAmount, remainingAfter);
    }

    private static CardAuthorizationStatus terminalStatus(String releaseReason) {
        return RELEASE_REASON_EXPIRY.equals(releaseReason)
                ? CardAuthorizationStatus.EXPIRED
                : CardAuthorizationStatus.REVERSED;
    }

    private static BigDecimal remainingHold(CardAuthorization auth) {
        return auth.amount().subtract(safeReleasedAmount(auth)).subtract(safeSettledAmount(auth));
    }

    private static BigDecimal safeReleasedAmount(CardAuthorization auth) {
        return auth.releasedAmount() != null ? auth.releasedAmount() : BigDecimal.ZERO;
    }

    private static BigDecimal safeSettledAmount(CardAuthorization auth) {
        return auth.settledAmount() != null ? auth.settledAmount() : BigDecimal.ZERO;
    }

    static String releaseEventId(String issuerId, String authorizationId, String releaseId, String releaseReason) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((issuerId + ":" + authorizationId + ":" + releaseReason + ":" + releaseId)
                    .getBytes(StandardCharsets.UTF_8));
            return "card_hold_rel_" + HexFormat.of().formatHex(hash).substring(0, 46);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
