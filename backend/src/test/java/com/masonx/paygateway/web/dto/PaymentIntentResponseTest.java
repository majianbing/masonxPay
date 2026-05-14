package com.masonx.paygateway.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentAttemptType;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentIntentResponseTest {

    @Test
    void from_includesSafeRetryAttemptMetadata() {
        UUID intentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent();
        ReflectionTestUtils.setField(intent, "id", intentId);
        intent.setMerchantId(UUID.randomUUID());
        intent.setAmount(1000L);
        intent.setCurrency("USD");
        intent.setMode(ApiKeyMode.TEST);
        intent.setStatus(PaymentIntentStatus.FAILED);
        intent.setCaptureMethod(CaptureMethod.AUTOMATIC);

        PaymentRequest attempt = new PaymentRequest();
        ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
        attempt.setPaymentIntentId(intentId);
        attempt.setAmount(1000L);
        attempt.setCurrency("USD");
        attempt.setPaymentMethodType("card");
        attempt.setStatus(PaymentRequestStatus.FAILED);
        attempt.setAttemptNumber(2);
        attempt.setAttemptType(PaymentAttemptType.SAME_ACCOUNT_RETRY);
        attempt.setConnectorAccountId(accountId);
        attempt.setProviderRequestId("ch_test_123");
        attempt.setProviderIdempotencyKey("pi-secret-operational-key");

        PaymentIntentResponse response = PaymentIntentResponse.from(
                intent, List.of(attempt), new ObjectMapper(), "Stripe test");

        PaymentIntentResponse.PaymentAttemptSummary summary = response.attempts().getFirst();
        assertThat(summary.attemptNumber()).isEqualTo(2);
        assertThat(summary.attemptType()).isEqualTo("SAME_ACCOUNT_RETRY");
        assertThat(summary.connectorAccountId()).isEqualTo(accountId);
        assertThat(summary.providerRequestId()).isEqualTo("ch_test_123");
    }
}
