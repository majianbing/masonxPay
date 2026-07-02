package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.po.VaAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsufficientBalanceValidatorTest {

    private final InsufficientBalanceValidator validator = new InsufficientBalanceValidator();

    private VaAccount account(String id) {
        return new VaAccount(id, Mode.LIVE, AccountRole.TENANT,
                "org_1", "mer_1", null,
                AccountType.CASH, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, AccountStatus.ACTIVE);
    }

    private EntryDraft debit(String amount) {
        return new EntryDraft("ac_1", Direction.DEBIT, new BigDecimal(amount), "USD", "evt_1");
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
