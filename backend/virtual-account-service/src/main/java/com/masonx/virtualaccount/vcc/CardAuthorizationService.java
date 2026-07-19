package com.masonx.virtualaccount.vcc;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.CardAuthorizationRepository;
import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.CardAuthHoldPostingRule;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthRequest;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issuer-agnostic real-time authorization decision core.
 *
 * <p>Decision flow per authorization:
 * <ol>
 *   <li>Serialize on (issuerId, authorizationId) using a transaction-scoped DB lock.
 *   <li>Replay: if a decision already exists for (issuerId, authorizationId),
 *       return it unchanged — duplicate deliveries must not re-decide.
 *   <li>Resolve the card and lock its PREPAID_CARD / PREPAID_CARD_HOLD accounts.
 *   <li>Check available balance and spending limit.
 *   <li>On approval, post DR PREPAID_CARD_HOLD / CR PREPAID_CARD and record
 *       the decision — atomically, in this transaction.
 * </ol>
 *
 * <p>Fail-closed invariant: an APPROVED response is always backed by a hold
 * journal attributable to this authorization. If the ledger inbox reports the
 * hold event as already consumed while no decision record exists, the request
 * is declined with {@link #REASON_AUTH_STATE_ANOMALY} — never approved unheld.
 * A wrong decline costs the cardholder a retry; a wrong approval promises
 * funds that were never reserved.
 */
@Service
public class CardAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(CardAuthorizationService.class);

    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_DECLINED = "DECLINED";

    public static final String REASON_CARD_NOT_FOUND     = "CARD_NOT_FOUND";
    public static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String REASON_AUTH_STATE_ANOMALY = "AUTH_STATE_ANOMALY";

    private final VirtualCardRepository       virtualCardRepo;
    private final LedgerAccountRepository     accountRepo;
    private final CardAuthorizationRepository authorizationRepo;
    private final LedgerFacade                ledger;
    private final CardAuthHoldPostingRule     authHoldPostingRule;
    private final SnowflakeIdGenerator        idGen;

    public CardAuthorizationService(VirtualCardRepository virtualCardRepo,
                                    LedgerAccountRepository accountRepo,
                                    CardAuthorizationRepository authorizationRepo,
                                    LedgerFacade ledger,
                                    CardAuthHoldPostingRule authHoldPostingRule,
                                    SnowflakeIdGenerator idGen) {
        this.virtualCardRepo     = virtualCardRepo;
        this.accountRepo         = accountRepo;
        this.authorizationRepo   = authorizationRepo;
        this.ledger              = ledger;
        this.authHoldPostingRule = authHoldPostingRule;
        this.idGen               = idGen;
    }

    @Transactional
    public IssuerAuthResponse authorize(String issuerId, IssuerAuthRequest req) {
        authorizationRepo.lockIdentity(issuerId, req.authorizationId());
        Optional<CardAuthorization> prior =
                authorizationRepo.findByIssuerIdAndAuthorizationId(issuerId, req.authorizationId());
        if (prior.isPresent()) {
            return replay(prior.get());
        }

        VirtualCard card = virtualCardRepo.findActiveByCardTokenId(req.cardTokenId()).orElse(null);
        if (card == null) {
            // No decision record: there is no card to attach it to, and the decline
            // replays deterministically from the same lookup.
            log.warn("Card auth decline: no active card for cardTokenId={} issuerId={} authorizationId={}",
                    req.cardTokenId(), issuerId, req.authorizationId());
            return new IssuerAuthResponse(DECISION_DECLINED, REASON_CARD_NOT_FOUND);
        }

        LedgerAccount cardAccount = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findByIdForUpdate(card.holdAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));

        prior = authorizationRepo.findByIssuerIdAndAuthorizationId(issuerId, req.authorizationId());
        if (prior.isPresent()) {
            return replay(prior.get());
        }

        BigDecimal available = cardAccount.balance();
        BigDecimal effectiveLimit = card.spendingLimit() != null
                ? card.spendingLimit().min(available)
                : available;

        if (req.amount().compareTo(effectiveLimit) > 0) {
            IssuerAuthResponse response = saveDecisionOrReplay(
                    issuerId, req, card, null, DECISION_DECLINED, REASON_INSUFFICIENT_FUNDS);
            log.info("Card auth decline: insufficient funds cardId={} requested={} available={}",
                    card.cardId(), req.amount(), available);
            return response;
        }

        String holdEventId = holdEventId(issuerId, req.authorizationId());
        boolean posted = ledger.postAllIfNew(
                authHoldPostingRule.build(new CardAuthHoldPostingRule.AuthHoldEvent(
                        card, cardAccount, holdAccount, req.amount(), req.currency(), holdEventId)),
                holdEventId,
                "card-auth");

        if (!posted) {
            IssuerAuthResponse response = saveDecisionOrReplay(
                    issuerId, req, card, holdEventId, DECISION_DECLINED, REASON_AUTH_STATE_ANOMALY);
            log.error("Card auth fail-closed decline: hold event already consumed but no decision record "
                            + "exists; issuerId={} authorizationId={} cardId={} holdEventId={}",
                    issuerId, req.authorizationId(), card.cardId(), holdEventId);
            return response;
        }

        IssuerAuthResponse response = saveDecisionOrReplay(
                issuerId, req, card, holdEventId, DECISION_APPROVED, null);
        log.info("Card auth approved: cardId={} amount={} issuerId={} authorizationId={}",
                card.cardId(), req.amount(), issuerId, req.authorizationId());
        return response;
    }

    private IssuerAuthResponse replay(CardAuthorization prior) {
        log.info("Card auth replay: issuerId={} authorizationId={} decision={} reason={}",
                prior.issuerId(), prior.authorizationId(), prior.decision(), prior.declineReason());
        return new IssuerAuthResponse(prior.decision(), prior.declineReason());
    }

    private IssuerAuthResponse saveDecisionOrReplay(String issuerId, IssuerAuthRequest req, VirtualCard card,
                                                    String holdEventId, String decision, String declineReason) {
        boolean inserted = authorizationRepo.insert(new CardAuthorization(
                    idGen.generate(MasonXIdPrefix.CARD_AUTHORIZATION.prefix()),
                    issuerId,
                    req.authorizationId(),
                    card.cardId(),
                    req.stan(),
                    req.rrn(),
                    req.amount(),
                    req.currency(),
                    decision,
                    declineReason,
                    holdEventId,
                    DECISION_APPROVED.equals(decision)
                            ? CardAuthorizationStatus.AUTHORIZED
                            : CardAuthorizationStatus.DECLINED,
                    Instant.now()));
        if (inserted) {
            return new IssuerAuthResponse(decision, declineReason);
        }
        return authorizationRepo.findByIssuerIdAndAuthorizationId(issuerId, req.authorizationId())
                .map(this::replay)
                .orElseThrow(() -> new IllegalStateException(
                        "Duplicate authorization identity without readable decision: issuerId=" + issuerId
                        + " authorizationId=" + req.authorizationId()));
    }

    /**
     * Deterministic ledger event id for this authorization's hold journal.
     * Hashing keeps the id bounded regardless of issuer token length.
     */
    static String holdEventId(String issuerId, String authorizationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((issuerId + ":" + authorizationId).getBytes(StandardCharsets.UTF_8));
            return "card_auth_" + HexFormat.of().formatHex(hash).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
