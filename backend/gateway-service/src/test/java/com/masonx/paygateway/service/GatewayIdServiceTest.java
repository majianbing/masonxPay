package com.masonx.paygateway.service;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayIdServiceTest {

    private final GatewayIdService service = new GatewayIdService(new SnowflakeIdGenerator(2));

    @Test
    void generate_uses_requested_prefix() {
        String id = service.generate(MasonXIdPrefix.PAYMENT_INTENT);

        assertThat(id).startsWith(MasonXIdPrefix.PAYMENT_INTENT.prefix());
        assertThat(id.substring(MasonXIdPrefix.PAYMENT_INTENT.prefix().length())).matches("\\d+");
    }

    @Test
    void generate_supports_different_gateway_resource_prefixes() {
        assertThat(service.generate(MasonXIdPrefix.REFUND)).startsWith("rf_");
        assertThat(service.generate(MasonXIdPrefix.PROVIDER_ACCOUNT)).startsWith("pa_");
        assertThat(service.generate(MasonXIdPrefix.MERCHANT)).startsWith("mer_");
    }

    @Test
    void generate_rejects_null_prefix() {
        assertThatThrownBy(() -> service.generate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("prefix");
    }
}
