package com.masonx.virtualaccount.vcc;

import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.CardAuthHoldPostingRule;
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
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Processes ISO 8583 authorization requests for VA-issued VCCs (BIN 999999).
 *
 * <p>Decision logic:
 * <ol>
 *   <li>Look up VirtualCard by maskedPan.
 *   <li>Reject if card is not ACTIVE.
 *   <li>Lock the PREPAID_CARD account (SELECT FOR UPDATE) within the transaction.
 *   <li>Check available balance (PREPAID_CARD.balance) ≥ requested amount.
 *   <li>Check spending limit if configured.
 *   <li>On approval: post DR PREPAID_CARD_HOLD / CR PREPAID_CARD.
 *   <li>Return APPROVED with auth code, or DECLINED with DE39 response code.
 * </ol>
 */
@Service
public class IssuerAuthService {

    private static final Logger log = LoggerFactory.getLogger(IssuerAuthService.class);

    private final VirtualCardRepository virtualCardRepo;
    private final LedgerAccountRepository     accountRepo;
    private final LedgerFacade          ledger;
    private final CardAuthHoldPostingRule authHoldPostingRule;

    public IssuerAuthService(VirtualCardRepository virtualCardRepo,
                              LedgerAccountRepository accountRepo,
                              LedgerFacade ledger,
                              CardAuthHoldPostingRule authHoldPostingRule) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
        this.ledger          = ledger;
        this.authHoldPostingRule = authHoldPostingRule;
    }

    @Transactional
    public IssuerAuthResponse authorize(IssuerAuthRequest req) {
        VirtualCard card = virtualCardRepo.findActiveByMaskedPan(req.maskedPan())
                .orElse(null);

        if (card == null) {
            log.warn("Issuer decline: card not found for maskedPan={}", req.maskedPan());
            return new IssuerAuthResponse("DECLINED", "14", null, "Card not found");
        }

        if (card.status() != VirtualCardStatus.ACTIVE) {
            log.warn("Issuer decline: card not active cardId={} status={}", card.cardId(), card.status());
            return new IssuerAuthResponse("DECLINED", "14", null, "Card not active");
        }

        LedgerAccount account = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));
        LedgerAccount holdAccount = accountRepo.findByIdForUpdate(card.holdAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD_HOLD account not found for card: " + card.cardId()));

        BigDecimal available = account.balance();

        // Check spending limit (if configured, use the lesser of limit and available balance).
        BigDecimal effectiveLimit = card.spendingLimit() != null
                ? card.spendingLimit().min(available)
                : available;

        if (req.amount().compareTo(effectiveLimit) > 0) {
            log.info("Issuer decline: insufficient funds cardId={} requested={} available={}",
                    card.cardId(), req.amount(), available);
            return new IssuerAuthResponse("DECLINED", "51", null, "Insufficient funds");
        }

        String eventId = authEventId(req);
        // TODO: on a duplicate eventId (retried auth with the same RRN/STAN), postAllIfNew
        // skips the second hold correctly, but we still mint and return a fresh random
        // authCode below instead of replaying the original one from the first attempt.
        ledger.postAllIfNew(
                authHoldPostingRule.build(
                        new CardAuthHoldPostingRule.AuthHoldEvent(card, account, holdAccount,
                                req.amount(), req.currency(), eventId)),
                eventId,
                "vcc-card-auth");

        String authCode = generateAuthCode();
        log.info("Issuer approved cardId={} amount={} authCode={}", card.cardId(), req.amount(), authCode);
        return new IssuerAuthResponse("APPROVED", "00", authCode, null);
    }

    private static String authEventId(IssuerAuthRequest req) {
        String stableRef = req.rrn() != null && !req.rrn().isBlank() ? req.rrn() : req.stan();
        String raw = req.maskedPan() + ":" + stableRef + ":" + req.amount() + ":" + req.currency();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "vcc_auth_" + HexFormat.of().formatHex(hash).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static String generateAuthCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
