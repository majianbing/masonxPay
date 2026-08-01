package com.masonx.virtualaccount.fee;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.PrepaidFeePostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentLine;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class PrepaidFeePostingService {

    private static final String LEDGER_EVENT_TYPE = "prepaid-fee";

    private final LedgerAccountRepository accountRepository;
    private final LedgerFacade ledger;
    private final PrepaidFeePostingRule postingRule;

    public PrepaidFeePostingService(LedgerAccountRepository accountRepository,
                                    LedgerFacade ledger,
                                    PrepaidFeePostingRule postingRule) {
        this.accountRepository = accountRepository;
        this.ledger = ledger;
        this.postingRule = postingRule;
    }

    @Transactional
    public boolean postAssessmentFeesFromWallet(PrepaidFeeAssessmentSnapshot snapshot, String walletAccountId) {
        LedgerAccount wallet = accountRepository.findById(walletAccountId)
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "Fee debit wallet account not found: " + walletAccountId));
        requireWalletScope(snapshot, wallet);

        Map<String, BigDecimal> totalsByCurrency = totalsByCurrency(snapshot.lines());
        if (totalsByCurrency.isEmpty()) {
            return false;
        }
        if (totalsByCurrency.size() != 1 || !totalsByCurrency.containsKey(wallet.asset())) {
            throw new BusinessException("VA_FEE_CURRENCY_MISMATCH",
                    "Prepaid fee currency must match wallet/account currency until FX conversion is supported");
        }

        BigDecimal amount = totalsByCurrency.get(wallet.asset());
        if (amount.signum() <= 0) {
            return false;
        }

        LedgerAccount feeIncome = accountRepository
                .findPlatformAccount(wallet.asset(), LedgerAccountType.FEE_INCOME)
                .orElseThrow(() -> new BusinessException("VA_ACCOUNT_NOT_FOUND",
                        "No FEE_INCOME account for asset: " + wallet.asset()));

        String postingEventId = postingEventId(snapshot);
        List<LedgerPostingCommand> commands = postingRule.build(
                new PrepaidFeePostingRule.PrepaidFeePostingEvent(
                        snapshot.assessment(), wallet, feeIncome, amount, wallet.asset(), postingEventId));
        return ledger.postAllIfNew(commands, postingEventId, LEDGER_EVENT_TYPE);
    }

    public static String postingEventId(PrepaidFeeAssessmentSnapshot snapshot) {
        return "fee:%s:%s:%s:%s".formatted(
                snapshot.assessment().merchantId(),
                snapshot.assessment().mode(),
                snapshot.assessment().eventType(),
                snapshot.assessment().eventId());
    }

    private static void requireWalletScope(PrepaidFeeAssessmentSnapshot snapshot, LedgerAccount wallet) {
        if (wallet.ledgerAccountRole() != LedgerAccountRole.TENANT
                || wallet.ledgerAccountType() != LedgerAccountType.WALLET
                || !snapshot.assessment().merchantId().equals(wallet.merchantId())
                || snapshot.assessment().mode() != wallet.mode()) {
            throw new BusinessException("VA_ACCOUNT_SCOPE_MISMATCH",
                    "Fee debit account must be the merchant wallet in the assessment tenant/mode");
        }
    }

    private static Map<String, BigDecimal> totalsByCurrency(List<PrepaidFeeAssessmentLine> lines) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (PrepaidFeeAssessmentLine line : lines) {
            if (line.amount() == null || line.amount().signum() <= 0) {
                continue;
            }
            totals.merge(line.currency(), line.amount(), BigDecimal::add);
        }
        return totals;
    }
}
