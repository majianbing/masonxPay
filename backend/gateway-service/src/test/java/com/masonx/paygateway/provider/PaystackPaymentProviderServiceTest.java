package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.provider.credentials.PaystackCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaystackPaymentProviderServiceTest {

    @Test
    void charge_returnsRedirectActionWithDeterministicReference() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackPaymentProviderService service = new PaystackPaymentProviderService(builder);

        server.expect(requestTo("https://api.paystack.co/transaction/initialize"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk_test_secret"))
                .andExpect(jsonPath("$.reference").value("idem-123"))
                .andExpect(jsonPath("$.amount").value(1234))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Authorization URL created","data":{"authorization_url":"https://checkout.paystack.com/abc","reference":"idem-123"}}
                        """, MediaType.APPLICATION_JSON));

        ChargeResult result = service.charge(chargeRequest(), creds());

        assertThat(result.requiresAction()).isTrue();
        assertThat(result.providerPaymentId()).isEqualTo("idem-123");
        assertThat(result.actionType()).isEqualTo("redirect_url");
        assertThat(result.actionUrl()).isEqualTo("https://checkout.paystack.com/abc");
        server.verify();
    }

    @Test
    void syncStatus_mapsSuccessfulVerificationToSucceeded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackPaymentProviderService service = new PaystackPaymentProviderService(builder);

        server.expect(requestTo(containsString("/transaction/verify/idem-123")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"data":{"reference":"idem-123","status":"success"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<PaymentIntentStatus> status = service.syncStatus("idem-123", creds());

        assertThat(status).contains(PaymentIntentStatus.SUCCEEDED);
        server.verify();
    }

    @Test
    void refund_postsReferenceAndAmount() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaystackPaymentProviderService service = new PaystackPaymentProviderService(builder);

        server.expect(requestTo("https://api.paystack.co/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transaction").value("idem-123"))
                .andExpect(jsonPath("$.amount").value(500))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Refund created","data":{"id":99}}
                        """, MediaType.APPLICATION_JSON));

        RefundResult result = service.refund(new RefundRequest(UUID.randomUUID(), "idem-123", 500, "customer_request"), creds());

        assertThat(result.success()).isTrue();
        assertThat(result.providerRefundId()).isEqualTo("99");
        server.verify();
    }

    @Test
    void mapStatus_handlesTerminalStatuses() {
        assertThat(PaystackPaymentProviderService.mapStatus("success")).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(PaystackPaymentProviderService.mapStatus("failed")).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(PaystackPaymentProviderService.mapStatus("abandoned")).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(PaystackPaymentProviderService.mapStatus("ongoing")).isNull();
    }

    private static ChargeRequest chargeRequest() {
        return new ChargeRequest(
                UUID.randomUUID(),
                1234,
                "usd",
                "card",
                "",
                null,
                "idem-123",
                new BillingDetails("Ada", "Lovelace", "ada@example.com", "+15551234567", null),
                null,
                CaptureMethod.AUTOMATIC,
                "https://merchant.example/return");
    }

    private static PaystackCredentials creds() {
        return new PaystackCredentials("sk_test_secret", "pk_test_public", true);
    }
}
