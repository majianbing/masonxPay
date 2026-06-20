package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceSignatureServiceTest {

    private final BalanceSignatureService service = new BalanceSignatureService("test-secret");

    private SignatureInput input(String prevSig) {
        return new SignatureInput(
                "ac_123", 1L,
                new BigDecimal("100.00"), Direction.CREDIT,
                new BigDecimal("100.00"), BigDecimal.ZERO,
                "tx_456", prevSig);
    }

    @Test
    void same_inputs_produce_same_signature() {
        String sig1 = service.compute(input("GENESIS"));
        String sig2 = service.compute(input("GENESIS"));
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void signature_is_64_char_hex() {
        String sig = service.compute(input("GENESIS"));
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void different_prev_signature_changes_output() {
        String sig1 = service.compute(input("GENESIS"));
        String sig2 = service.compute(input("other-sig"));
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void different_amount_changes_output() {
        var input1 = new SignatureInput("ac_1", 1L, new BigDecimal("100.00"), Direction.CREDIT,
                new BigDecimal("100.00"), BigDecimal.ZERO, "tx_1", "GENESIS");
        var input2 = new SignatureInput("ac_1", 1L, new BigDecimal("200.00"), Direction.CREDIT,
                new BigDecimal("200.00"), BigDecimal.ZERO, "tx_1", "GENESIS");
        assertThat(service.compute(input1)).isNotEqualTo(service.compute(input2));
    }

    @Test
    void different_frozen_balance_changes_output() {
        var input1 = new SignatureInput("ac_1", 1L, new BigDecimal("100.00"), Direction.CREDIT,
                new BigDecimal("100.00"), BigDecimal.ZERO, "tx_1", "GENESIS");
        var input2 = new SignatureInput("ac_1", 1L, new BigDecimal("100.00"), Direction.CREDIT,
                new BigDecimal("100.00"), new BigDecimal("50.00"), "tx_1", "GENESIS");
        assertThat(service.compute(input1)).isNotEqualTo(service.compute(input2));
    }

    @Test
    void verify_returns_true_for_correct_signature() {
        var i = input("GENESIS");
        assertThat(service.verify(i, service.compute(i))).isTrue();
    }

    @Test
    void verify_returns_false_for_tampered_signature() {
        assertThat(service.verify(input("GENESIS"), "deadbeef")).isFalse();
    }

    @Test
    void chain_links_correctly() {
        String sig1 = service.compute(input("GENESIS"));
        var input2 = new SignatureInput("ac_123", 2L,
                new BigDecimal("50.00"), Direction.DEBIT,
                new BigDecimal("50.00"), BigDecimal.ZERO,
                "tx_789", sig1);
        String sig2 = service.compute(input2);
        assertThat(sig2).isNotEqualTo(sig1);
        assertThat(service.verify(input2, sig2)).isTrue();
    }
}
