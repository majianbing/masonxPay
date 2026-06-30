package com.masonx.virtualaccount.domain.ledger.validator;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.TransactionType;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetConsistencyValidatorTest {

    private final AssetConsistencyValidator validator = new AssetConsistencyValidator();

    private static PostTransaction tx(List<EntryDraft> entries) {
        return new PostTransaction("tx_1", entries,
                TransactionType.INTERNAL, null, null,
                LocalDate.of(2026, 1, 1), Mode.LIVE, null, null);
    }

    @Test
    void passes_when_all_entries_share_same_asset() {
        var tx = tx(List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100"), "USD", "evt_1")));

        assertThatNoException().isThrownBy(() -> validator.validate(tx));
    }

    @Test
    void passes_for_single_entry() {
        var tx = tx(List.of(
                new EntryDraft("ac_1", Direction.DEBIT, new BigDecimal("50"), "EUR", "evt_1")));

        assertThatNoException().isThrownBy(() -> validator.validate(tx));
    }

    @Test
    void rejects_entries_with_mixed_assets() {
        var tx = tx(List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100"), "BTC", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    var be = (BusinessException) ex;
                    assert be.code().equals("VA_ASSET_MISMATCH");
                });
    }

    @Test
    void rejects_three_way_split_with_mixed_assets() {
        var tx = tx(List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("50"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("30"), "USD", "evt_1"),
                new EntryDraft("ac_3", Direction.CREDIT, new BigDecimal("20"), "EUR", "evt_1")));

        assertThatThrownBy(() -> validator.validate(tx))
                .isInstanceOf(BusinessException.class);
    }
}
