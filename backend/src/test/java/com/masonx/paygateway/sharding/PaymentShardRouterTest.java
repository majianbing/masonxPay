package com.masonx.paygateway.sharding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentShardRouterTest {

    private final PaymentShardingProperties properties = new PaymentShardingProperties();
    private final PaymentShardRouter router = new PaymentShardRouter(properties);

    @Test
    void shardForPaymentId_isStableAndInRange() {
        UUID paymentId = UUID.fromString("11111111-1111-4111-8111-111111111111");

        int first = router.shardForPaymentId(paymentId);
        int second = router.shardForPaymentId(paymentId);

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 63);
    }

    @Test
    void paymentIntentAndRequestUseSamePaymentShard() {
        UUID paymentId = UUID.fromString("22222222-2222-4222-8222-222222222222");

        assertThat(router.paymentIntentsTable(paymentId).replace("payment_intents_", ""))
                .isEqualTo(router.paymentRequestsTable(paymentId).replace("payment_requests_", ""));
    }

    @Test
    void paymentTableMatchesShardingSphereInlineHash() {
        UUID paymentId = UUID.fromString("1274521d-66cd-4445-ad86-3b94ef79624f");

        assertThat(router.paymentIntentsTable(paymentId)).isEqualTo("payment_intents_03");
    }

    @Test
    void idempotencyTableRoutesByMerchantAndKey() {
        UUID merchantId = UUID.fromString("33333333-3333-4333-8333-333333333333");

        String table = router.idempotencyKeysTable(merchantId, "checkout-123");

        assertThat(table).startsWith("payment_idempotency_keys_");
        assertThat(table).matches("payment_idempotency_keys_\\d{2}");
        assertThat(router.idempotencyKeysTable(merchantId, "checkout-123")).isEqualTo(table);
    }

    @Test
    void providerPaymentRefsNormalizeProviderName() {
        UUID accountId = UUID.fromString("44444444-4444-4444-8444-444444444444");

        String upper = router.providerPaymentRefsTable("STRIPE", accountId, "pi_123");
        String lower = router.providerPaymentRefsTable("stripe", accountId, "pi_123");

        assertThat(lower).isEqualTo(upper);
    }

    @Test
    void suffixForShardUsesTwoDigitsForSixtyFourShards() {
        assertThat(router.suffixForShard(0)).isEqualTo("00");
        assertThat(router.suffixForShard(9)).isEqualTo("09");
        assertThat(router.suffixForShard(63)).isEqualTo("63");
    }

    @Test
    void suffixForShardRejectsOutOfRangeShard() {
        assertThatThrownBy(() -> router.suffixForShard(64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 63");
    }

    @Test
    void paymentShardDistributionCoversMostShards() {
        Set<Integer> shards = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            shards.add(router.shardForPaymentId(
                    UUID.nameUUIDFromBytes(("payment-" + i).getBytes(StandardCharsets.UTF_8))));
        }

        assertThat(shards.size()).isGreaterThanOrEqualTo(56);
    }
}
