package com.masonx.common.card;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatorCardTokenIdTest {

    @Test
    void fromPan_is_deterministic_and_bounded() {
        String first = SimulatorCardTokenId.fromPan("9999991234567890");
        String second = SimulatorCardTokenId.fromPan("9999991234567890");

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("ctok_");
        assertThat(first).hasSize(53);
    }

    @Test
    void fromPan_changes_with_pan() {
        assertThat(SimulatorCardTokenId.fromPan("9999991234567890"))
                .isNotEqualTo(SimulatorCardTokenId.fromPan("9999991234567891"));
    }

    @Test
    void fromPan_rejects_blank_pan() {
        assertThatThrownBy(() -> SimulatorCardTokenId.fromPan(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
