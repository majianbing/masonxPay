package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.config.LedgerSignatureProperties;
import com.masonx.virtualaccount.domain.constant.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceSignatureServiceTest {

    private final BalanceSignatureService service = new BalanceSignatureService("test-secret");

    private SignatureInput input(String prevSig) {
        return new SignatureInput(
                "ac_123", 1L,
                new BigDecimal("100.00"), "USD", Direction.CREDIT,
                new BigDecimal("100.00"),
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
        var input1 = new SignatureInput("ac_1", 1L, new BigDecimal("100.00"), "USD", Direction.CREDIT,
                new BigDecimal("100.00"), "tx_1", "GENESIS");
        var input2 = new SignatureInput("ac_1", 1L, new BigDecimal("200.00"), "USD", Direction.CREDIT,
                new BigDecimal("200.00"), "tx_1", "GENESIS");
        assertThat(service.compute(input1)).isNotEqualTo(service.compute(input2));
    }

    @Test
    void different_asset_changes_output() {
        var input1 = new SignatureInput("ac_1", 1L, new BigDecimal("100.00"), "USD", Direction.CREDIT,
                new BigDecimal("100.00"), "tx_1", "GENESIS");
        var input2 = new SignatureInput("ac_1", 1L, new BigDecimal("100.00"), "EUR", Direction.CREDIT,
                new BigDecimal("100.00"), "tx_1", "GENESIS");
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
    void scale_variants_produce_same_signature() {
        // DB NUMERIC(38,8) returns scale-8; request body BigDecimal has scale-2 or scale-0.
        // canonical() must be invariant to scale.
        var withScale2 = new SignatureInput("ac_1", 1L,
                new BigDecimal("10.00"), "USD", Direction.DEBIT,
                new BigDecimal("10.00"), "tx_1", "GENESIS");
        var withScale8 = new SignatureInput("ac_1", 1L,
                new BigDecimal("10.00000000"), "USD", Direction.DEBIT,
                new BigDecimal("10.00000000"), "tx_1", "GENESIS");
        assertThat(service.compute(withScale2)).isEqualTo(service.compute(withScale8));
    }

    @Test
    void chain_links_correctly() {
        String sig1 = service.compute(input("GENESIS"));
        var input2 = new SignatureInput("ac_123", 2L,
                new BigDecimal("50.00"), "USD", Direction.DEBIT,
                new BigDecimal("50.00"),
                "tx_789", sig1);
        String sig2 = service.compute(input2);
        assertThat(sig2).isNotEqualTo(sig1);
        assertThat(service.verify(input2, sig2)).isTrue();
    }

    @Test
    void active_key_id_is_used_for_new_signatures() {
        LedgerSignatureProperties props = new LedgerSignatureProperties();
        props.setActiveKeyId("v2");
        props.setKeys(Map.of("v1", "old-secret", "v2", "new-secret"));
        var rotated = new BalanceSignatureService(props);

        assertThat(rotated.activeKeyId()).isEqualTo("v2");

        var input = new SignatureInput("ac_1", 1L,
                new BigDecimal("10.00"), "USD", Direction.DEBIT,
                new BigDecimal("10.00"), "tx_1", "GENESIS", rotated.activeKeyId());
        assertThat(rotated.verify(input, rotated.compute(input))).isTrue();
    }

    @Test
    void historical_key_id_keeps_old_signature_verifiable_after_rotation() {
        LedgerSignatureProperties oldProps = new LedgerSignatureProperties();
        oldProps.setActiveKeyId("v1");
        oldProps.setKeys(Map.of("v1", "old-secret"));
        var oldService = new BalanceSignatureService(oldProps);

        var oldInput = new SignatureInput("ac_1", 1L,
                new BigDecimal("10.00"), "USD", Direction.DEBIT,
                new BigDecimal("10.00"), "tx_1", "GENESIS", "v1");
        String oldSignature = oldService.compute(oldInput);

        LedgerSignatureProperties rotatedProps = new LedgerSignatureProperties();
        rotatedProps.setActiveKeyId("v2");
        rotatedProps.setKeys(Map.of("v1", "old-secret", "v2", "new-secret"));
        var rotatedService = new BalanceSignatureService(rotatedProps);

        assertThat(rotatedService.verify(oldInput, oldSignature)).isTrue();
    }
}
