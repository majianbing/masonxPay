package com.masonx.paygateway.sharding;

import java.util.UUID;

public record PaymentRoute(UUID paymentIntentId, int paymentShardId) {
}
