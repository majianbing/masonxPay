package com.masonx.common.id;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MasonXIdPrefixTest {

    @Test
    void prefixes_are_unique() {
        var prefixes = Arrays.stream(MasonXIdPrefix.values())
                .map(MasonXIdPrefix::prefix)
                .collect(Collectors.toSet());

        assertThat(prefixes).hasSize(MasonXIdPrefix.values().length);
    }

    @Test
    void prefixes_use_lowercase_resource_marker_format() {
        assertThat(Arrays.stream(MasonXIdPrefix.values()).map(MasonXIdPrefix::prefix))
                .allMatch(prefix -> prefix.matches("[a-z][a-z0-9_]*_"));
    }

    @Test
    void includes_existing_va_and_rail_prefixes() {
        assertThat(MasonXIdPrefix.VA_ACCOUNT.prefix()).isEqualTo("ac_");
        assertThat(MasonXIdPrefix.LEDGER_ENTRY.prefix()).isEqualTo("le_");
        assertThat(MasonXIdPrefix.LEDGER_TRANSACTION.prefix()).isEqualTo("tx_");
        assertThat(MasonXIdPrefix.LEDGER_RAIL_TRANSACTION.prefix()).isEqualTo("tx_rail_");
        assertThat(MasonXIdPrefix.CARD_FUND_TRANSACTION.prefix()).isEqualTo("tx_fund_");
        assertThat(MasonXIdPrefix.CARD_CLOSE_TRANSACTION.prefix()).isEqualTo("tx_close_");
        assertThat(MasonXIdPrefix.VCC_ACCOUNT.prefix()).isEqualTo("va_");
        assertThat(MasonXIdPrefix.VIRTUAL_CARD.prefix()).isEqualTo("vc_");
        assertThat(MasonXIdPrefix.RAIL_PAYMENT.prefix()).isEqualTo("rp_");
        assertThat(MasonXIdPrefix.RAIL_ROUTING_DECISION.prefix()).isEqualTo("rd_");
        assertThat(MasonXIdPrefix.ISO8583_LOG.prefix()).isEqualTo("iso_");
        assertThat(MasonXIdPrefix.NETWORK_CORRELATION.prefix()).isEqualTo("corr_");
        assertThat(MasonXIdPrefix.REVERSAL_TASK.prefix()).isEqualTo("rtask_");
        assertThat(MasonXIdPrefix.EVENT.prefix()).isEqualTo("evt_");
        assertThat(MasonXIdPrefix.ISO20022_MESSAGE.prefix()).isEqualTo("m20_");
        assertThat(MasonXIdPrefix.ISO20022_INSTRUCTION.prefix()).isEqualTo("ins_");
        assertThat(MasonXIdPrefix.ISO20022_END_TO_END.prefix()).isEqualTo("e2e_");
        assertThat(MasonXIdPrefix.ISO20022_LOG.prefix()).isEqualTo("i20_");
    }

    @Test
    void includes_gateway_payment_core_prefixes() {
        assertThat(MasonXIdPrefix.PAYMENT_INTENT.prefix()).isEqualTo("pi_");
        assertThat(MasonXIdPrefix.PAYMENT_REQUEST.prefix()).isEqualTo("pr_");
        assertThat(MasonXIdPrefix.REFUND.prefix()).isEqualTo("rf_");
        assertThat(MasonXIdPrefix.PROVIDER_ACCOUNT.prefix()).isEqualTo("pa_");
        assertThat(MasonXIdPrefix.WEBHOOK_ENDPOINT.prefix()).isEqualTo("whe_");
        assertThat(MasonXIdPrefix.WEBHOOK_DELIVERY.prefix()).isEqualTo("whd_");
    }

    @Test
    void snowflake_generator_accepts_registered_prefix() {
        var gen = new SnowflakeIdGenerator(0);

        String id = gen.generate(MasonXIdPrefix.PAYMENT_INTENT.prefix());

        assertThat(id).startsWith(MasonXIdPrefix.PAYMENT_INTENT.prefix());
        assertThat(id.substring(MasonXIdPrefix.PAYMENT_INTENT.prefix().length())).matches("\\d+");
    }
}
