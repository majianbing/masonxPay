package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetZeroValidatorTest {

    private final NetZeroValidator validator = new NetZeroValidator();

    @Test
    void passes_when_debits_equal_credits() {
        var tx = new PostTransaction("tx_1", List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100.00"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100.00"), "USD", "evt_1")));

        assertThatNoException().isThrownBy(() -> validator.validate(tx));
    }

    @Test
    void passes_for_three_way_split_that_balances() {
        var tx = new PostTransaction("tx_1", List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100.00"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("60.00"),  "USD", "evt_1"),
                new EntryDraft("ac_3", Direction.CREDIT, new BigDecimal("40.00"),  "USD", "evt_1")));

        assertThatNoException().isThrownBy(() -> validator.validate(tx));
    }

    @Test
    void rejects_when_debits_exceed_credits() {
        var tx = new PostTransaction("tx_1", List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100.00"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("90.00"),  "USD", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    var be = (BusinessException) ex;
                    assert be.code().equals("VA_NOT_BALANCED");
                });
    }

    @Test
    void rejects_when_credits_exceed_debits() {
        var tx = new PostTransaction("tx_1", List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("50.00"),  "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100.00"), "USD", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejects_debit_only_transaction() {
        var tx = new PostTransaction("tx_1", List.of(
                new EntryDraft("ac_1", Direction.DEBIT, new BigDecimal("100.00"), "USD", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class);
    }
}
