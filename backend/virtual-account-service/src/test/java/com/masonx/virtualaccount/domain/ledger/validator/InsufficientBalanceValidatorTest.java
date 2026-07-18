package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsufficientBalanceValidatorTest {

    private final InsufficientBalanceValidator validator = new InsufficientBalanceValidator();

    private LedgerAccount account(String id) {
        return new LedgerAccount(id, Mode.LIVE, LedgerAccountRole.TENANT,
                "org_1", "mer_1", null,
                LedgerAccountType.CASH, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }

    private AccountingEntryDraft debit(String amount) {
        return new AccountingEntryDraft("ac_1", Direction.DEBIT, new BigDecimal(amount), "USD", "evt_1");
    }

    @Test
    void passes_when_new_balance_is_positive() {
        assertThatNoException().isThrownBy(() ->
                validator.validate(debit("100"), account("ac_1"), new BigDecimal("50.00")));
    }

    @Test
    void passes_when_new_balance_is_exactly_zero() {
        assertThatNoException().isThrownBy(() ->
                validator.validate(debit("100"), account("ac_1"), BigDecimal.ZERO));
    }

    @Test
    void rejects_when_new_balance_is_negative() {
        assertThatThrownBy(() ->
                validator.validate(debit("100"), account("ac_1"), new BigDecimal("-0.01")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    var be = (BusinessException) ex;
                    assert be.code().equals("VA_INSUFFICIENT_BALANCE");
                });
    }

    @Test
    void rejects_large_negative_balance() {
        assertThatThrownBy(() ->
                validator.validate(debit("100"), account("ac_1"), new BigDecimal("-999.99")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ac_1");
    }
}
