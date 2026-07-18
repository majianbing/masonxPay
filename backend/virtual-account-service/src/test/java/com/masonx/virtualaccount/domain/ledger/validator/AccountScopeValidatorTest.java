package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.LedgerAccountRole;
import com.masonx.virtualaccount.domain.constant.LedgerAccountStatus;
import com.masonx.virtualaccount.domain.constant.LedgerAccountType;
import com.masonx.virtualaccount.domain.constant.AssetClass;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class AccountScopeValidatorTest {

    private final AccountScopeValidator validator = new AccountScopeValidator();

    @Test
    void validate_accepts_matching_tenant_account() {
        validator.validate(tx(Mode.LIVE, "mer_1"), draft("USD"), tenantAccount(Mode.LIVE, "mer_1", "USD"),
                BigDecimal.ZERO);
    }

    @Test
    void validate_rejects_asset_mismatch() {
        assertThatThrownBy(() -> validator.validate(tx(Mode.LIVE, "mer_1"), draft("USD"),
                tenantAccount(Mode.LIVE, "mer_1", "EUR"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo("VA_ACCOUNT_ASSET_MISMATCH"));
    }

    @Test
    void validate_rejects_mode_mismatch() {
        assertThatThrownBy(() -> validator.validate(tx(Mode.TEST, "mer_1"), draft("USD"),
                tenantAccount(Mode.LIVE, "mer_1", "USD"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo("VA_ACCOUNT_MODE_MISMATCH"));
    }

    @Test
    void validate_rejects_tenant_merchant_mismatch() {
        assertThatThrownBy(() -> validator.validate(tx(Mode.LIVE, "mer_2"), draft("USD"),
                tenantAccount(Mode.LIVE, "mer_1", "USD"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo("VA_ACCOUNT_TENANT_MISMATCH"));
    }

    @Test
    void validate_allows_external_account_without_merchant_match() {
        validator.validate(tx(Mode.LIVE, "mer_1"), draft("USD"), externalAccount(), BigDecimal.ZERO);
    }

    private static LedgerPostingCommand tx(Mode mode, String merchantId) {
        return new LedgerPostingCommand("tx_1", List.of(draft("USD")),
                TransactionType.INTERNAL, null, null,
                LocalDate.of(2026, 1, 1), mode, "org_1", merchantId);
    }

    private static AccountingEntryDraft draft(String asset) {
        return new AccountingEntryDraft("ac_1", Direction.DEBIT, new BigDecimal("10.00"), asset, "evt_1");
    }

    private static LedgerAccount tenantAccount(Mode mode, String merchantId, String asset) {
        return new LedgerAccount("ac_1", mode, LedgerAccountRole.TENANT,
                "org_1", merchantId, null,
                LedgerAccountType.WALLET, asset, AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount externalAccount() {
        return new LedgerAccount("ac_ext", Mode.LIVE, LedgerAccountRole.EXTERNAL,
                null, null, "provider_1",
                LedgerAccountType.CLEARING, "USD", AssetClass.FIAT, 2,
                NormalBalance.CREDIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }
}
