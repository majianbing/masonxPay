package com.masonx.virtualaccount.domain;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Posts a settlement event as a balanced set of ledger entries.
 *
 * Direction = CREDIT (money in for merchant):
 *   Without fee (2 entries):
 *     DEBIT  tenant CASH      netAmount
 *     CREDIT external CLEARING netAmount
 *
 *   With fee and platform account present (3 entries):
 *     DEBIT  tenant CASH        netAmount
 *     DEBIT  platform FEE_INCOME feeAmount
 *     CREDIT external CLEARING  amount (gross)
 *
 * Direction = DEBIT (refund reversal, money out):
 *   CREDIT tenant CASH      amount
 *   DEBIT  external CLEARING amount
 *
 * TENANT CASH and platform FEE_INCOME are DEBIT-normal (asset perspective).
 * EXTERNAL CLEARING is CREDIT-normal (inflow source).
 *
 * Idempotency: the inbox layer (va_inbox_event) prevents the same event from
 * reaching this handler twice. The UNIQUE(account_id, source_event_id) constraint
 * on va_ledger_entry is a secondary safeguard.
 */
@Component
public class LedgerSettlementHandler implements SettlementHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerSettlementHandler.class);

    private final AccountRepository    accountRepo;
    private final LedgerFacade         ledger;
    private final SnowflakeIdGenerator idGenerator;

    public LedgerSettlementHandler(AccountRepository accountRepo,
                                   LedgerFacade ledger,
                                   SnowflakeIdGenerator idGenerator) {
        this.accountRepo = accountRepo;
        this.ledger      = ledger;
        this.idGenerator = idGenerator;
    }

    @Override
    public void handle(RecordSettlementCommand cmd) {
        if (cmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Skipping zero-amount settlement: eventId={}", cmd.sourceEventId());
            return;
        }

        String merchantId = cmd.tenant().merchantId().value().toString();

        VaAccount tenantCash = accountRepo
                .findTenantAccount(merchantId, cmd.tenant().mode(), cmd.asset(), AccountType.CASH)
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "No CASH account for merchant: " + merchantId
                        + " mode=" + cmd.tenant().mode() + " asset=" + cmd.asset()));

        VaAccount externalClearing = accountRepo
                .findExternalAccount(cmd.providerRef(), cmd.asset(), AccountType.CLEARING)
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "No CLEARING account for provider: " + cmd.providerRef()
                        + " asset=" + cmd.asset()));

        String txId    = idGenerator.generate("tx_");
        List<EntryDraft> entries = buildEntries(cmd, tenantCash, externalClearing);

        boolean posted = ledger.postIfNew(
                new PostTransaction(txId, entries),
                cmd.sourceEventId(), "settlement");

        if (posted) {
            log.info("VA settlement posted: eventId={} txId={} merchant={} amount={} asset={}",
                    cmd.sourceEventId(), txId, merchantId, cmd.netAmount(), cmd.asset());
        } else {
            log.info("VA settlement duplicate skipped: eventId={}", cmd.sourceEventId());
        }
    }

    private List<EntryDraft> buildEntries(RecordSettlementCommand cmd,
                                          VaAccount tenantCash,
                                          VaAccount externalClearing) {
        boolean moneyIn = cmd.direction() == Direction.CREDIT;

        if (moneyIn) {
            return buildSettlementEntries(cmd, tenantCash, externalClearing);
        } else {
            // Refund reversal: money flows back to provider
            return List.of(
                    new EntryDraft(tenantCash.accountId(),      Direction.CREDIT, cmd.amount(), cmd.asset(), cmd.sourceEventId()),
                    new EntryDraft(externalClearing.accountId(), Direction.DEBIT, cmd.amount(), cmd.asset(), cmd.sourceEventId())
            );
        }
    }

    private List<EntryDraft> buildSettlementEntries(RecordSettlementCommand cmd,
                                                     VaAccount tenantCash,
                                                     VaAccount externalClearing) {
        boolean hasFee = cmd.feeAmount().compareTo(BigDecimal.ZERO) > 0;

        if (hasFee) {
            var platformFeeOpt = accountRepo.findPlatformAccount(cmd.asset(), AccountType.FEE_INCOME);
            if (platformFeeOpt.isPresent()) {
                // 3-entry: DEBIT tenant (net) + DEBIT platform fee = CREDIT external (gross)
                return List.of(
                        new EntryDraft(tenantCash.accountId(),              Direction.DEBIT, cmd.netAmount(),  cmd.asset(), cmd.sourceEventId()),
                        new EntryDraft(platformFeeOpt.get().accountId(),    Direction.DEBIT, cmd.feeAmount(),  cmd.asset(), cmd.sourceEventId()),
                        new EntryDraft(externalClearing.accountId(),        Direction.CREDIT, cmd.amount(),    cmd.asset(), cmd.sourceEventId())
                );
            }
        }

        // 2-entry net settlement (no fee, or no platform fee account registered)
        BigDecimal net = hasFee ? cmd.netAmount() : cmd.amount();
        List<EntryDraft> entries = new ArrayList<>();
        entries.add(new EntryDraft(tenantCash.accountId(),      Direction.DEBIT,  net, cmd.asset(), cmd.sourceEventId()));
        entries.add(new EntryDraft(externalClearing.accountId(), Direction.CREDIT, net, cmd.asset(), cmd.sourceEventId()));
        return entries;
    }
}
