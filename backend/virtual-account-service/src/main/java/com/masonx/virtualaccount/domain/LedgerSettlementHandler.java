package com.masonx.virtualaccount.domain;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.api.SettlementHandler;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.posting.GatewaySettlementPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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
 * reaching this handler twice. The UNIQUE(ledger_account_id, source_event_id) constraint
 * on va_ledger_entry is a secondary safeguard.
 */
@Component
public class LedgerSettlementHandler implements SettlementHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerSettlementHandler.class);

    private final LedgerAccountRepository    accountRepo;
    private final LedgerFacade         ledger;
    private final GatewaySettlementPostingRule postingRule;

    public LedgerSettlementHandler(LedgerAccountRepository accountRepo,
                                   LedgerFacade ledger,
                                   GatewaySettlementPostingRule postingRule) {
        this.accountRepo = accountRepo;
        this.ledger      = ledger;
        this.postingRule = postingRule;
    }

    @Override
    public void handle(RecordSettlementCommand cmd) {
        if (cmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Skipping zero-amount settlement: eventId={}", cmd.sourceEventId());
            return;
        }

        String merchantId = cmd.tenant().merchantId().value().toString();

        LedgerAccount tenantCash = accountRepo
                .findTenantAccount(merchantId, cmd.tenant().mode(), cmd.asset(), LedgerAccountType.CASH)
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "No CASH account for merchant: " + merchantId
                        + " mode=" + cmd.tenant().mode() + " asset=" + cmd.asset()));

        LedgerAccount externalClearing = accountRepo
                .findExternalAccount(cmd.providerRef(), cmd.asset(), LedgerAccountType.CLEARING)
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "No CLEARING account for provider: " + cmd.providerRef()
                        + " asset=" + cmd.asset()));

        var commands = postingRule.build(
                new GatewaySettlementPostingRule.SettlementEvent(cmd, merchantId, tenantCash, externalClearing));
        boolean posted = ledger.postAllIfNew(commands, cmd.sourceEventId(), "settlement");
        String txId = commands.isEmpty() ? null : commands.get(0).transactionId();

        if (posted) {
            log.info("VA settlement posted: eventId={} txId={} merchant={} amount={} asset={}",
                    cmd.sourceEventId(), txId, merchantId, cmd.netAmount(), cmd.asset());
        } else {
            log.info("VA settlement duplicate skipped: eventId={}", cmd.sourceEventId());
        }
    }
}
