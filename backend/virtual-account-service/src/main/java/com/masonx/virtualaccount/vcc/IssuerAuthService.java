package com.masonx.virtualaccount.vcc;

import com.masonx.virtualaccount.domain.VirtualCardRepository;
import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthRequest;
import com.masonx.virtualaccount.vcc.dto.IssuerAuthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Processes ISO 8583 authorization requests for VA-issued VCCs (BIN 999999).
 *
 * <p>Decision logic:
 * <ol>
 *   <li>Look up VirtualCard by maskedPan.
 *   <li>Reject if card is not ACTIVE.
 *   <li>Lock the PREPAID_CARD account (SELECT FOR UPDATE) within the transaction.
 *   <li>Check available balance (balance − frozenBalance) ≥ requested amount.
 *   <li>Check spending limit if configured.
 *   <li>On approval: freeze the amount (frozenBalance += amount).
 *   <li>Return APPROVED with auth code, or DECLINED with DE39 response code.
 * </ol>
 *
 * <p>The freeze is a direct update to {@code va_account.frozen_balance}. It does NOT
 * post a ledger entry — the HMAC chain is not broken because the chain's integrity check
 * uses the {@code frozen_balance} stored in each entry's row (historical snapshot),
 * not the current row value. Settlement (MR4) posts the actual ledger entry.
 */
@Service
public class IssuerAuthService {

    private static final Logger log = LoggerFactory.getLogger(IssuerAuthService.class);

    private final VirtualCardRepository virtualCardRepo;
    private final AccountRepository     accountRepo;

    public IssuerAuthService(VirtualCardRepository virtualCardRepo,
                              AccountRepository accountRepo) {
        this.virtualCardRepo = virtualCardRepo;
        this.accountRepo     = accountRepo;
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

        VaAccount account = accountRepo.findByIdForUpdate(card.vccAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "PREPAID_CARD account not found for card: " + card.cardId()));

        BigDecimal available = account.availableBalance();

        // Check spending limit (if configured, use the lesser of limit and available balance).
        BigDecimal effectiveLimit = card.spendingLimit() != null
                ? card.spendingLimit().min(available)
                : available;

        if (req.amount().compareTo(effectiveLimit) > 0) {
            log.info("Issuer decline: insufficient funds cardId={} requested={} available={}",
                    card.cardId(), req.amount(), available);
            return new IssuerAuthResponse("DECLINED", "51", null, "Insufficient funds");
        }

        // Freeze the authorization amount.
        BigDecimal newFrozen = account.frozenBalance().add(req.amount());
        accountRepo.updateFrozenBalance(account.accountId(), newFrozen);

        String authCode = generateAuthCode();
        log.info("Issuer approved cardId={} amount={} authCode={}", card.cardId(), req.amount(), authCode);
        return new IssuerAuthResponse("APPROVED", "00", authCode, null);
    }

    private static String generateAuthCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
