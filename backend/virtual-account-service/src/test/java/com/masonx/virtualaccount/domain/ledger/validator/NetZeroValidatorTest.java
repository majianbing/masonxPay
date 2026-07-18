package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetZeroValidatorTest {

    private final NetZeroValidator validator = new NetZeroValidator();

    private static LedgerPostingCommand tx(List<AccountingEntryDraft> entries) {
        return new LedgerPostingCommand("tx_1", entries,
                TransactionType.INTERNAL, null, null,
                LocalDate.of(2026, 1, 1), Mode.LIVE, null, null);
    }

    @Test
    void passes_when_debits_equal_credits() {
        var tx = tx(List.of(
                new AccountingEntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100.00"), "USD", "evt_1"),
                new AccountingEntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100.00"), "USD", "evt_1")));

        assertThatNoException().isThrownBy(() -> validator.validate(tx));
    }

    @Test
    void passes_for_three_way_split_that_balances() {
        var tx = tx(List.of(
                new AccountingEntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100.00"), "USD", "evt_1"),
                new AccountingEntryDraft("ac_2", Direction.CREDIT, new BigDecimal("60.00"),  "USD", "evt_1"),
                new AccountingEntryDraft("ac_3", Direction.CREDIT, new BigDecimal("40.00"),  "USD", "evt_1")));

        assertThatNoException().isThrownBy(() -> validator.validate(tx));
    }

    @Test
    void rejects_when_debits_exceed_credits() {
        var tx = tx(List.of(
                new AccountingEntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100.00"), "USD", "evt_1"),
                new AccountingEntryDraft("ac_2", Direction.CREDIT, new BigDecimal("90.00"),  "USD", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    var be = (BusinessException) ex;
                    assert be.code().equals("VA_NOT_BALANCED");
                });
    }

    @Test
    void rejects_when_credits_exceed_debits() {
        var tx = tx(List.of(
                new AccountingEntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("50.00"),  "USD", "evt_1"),
                new AccountingEntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100.00"), "USD", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_debit_only_transaction() {
        var tx = tx(List.of(
                new AccountingEntryDraft("ac_1", Direction.DEBIT, new BigDecimal("100.00"), "USD", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class);
    }
}
