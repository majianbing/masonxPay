package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.provider.credentials.FlutterwaveCredentials;
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

class FlutterwavePaymentProviderServiceTest {

    @Test
    void charge_returnsRedirectActionWithDeterministicTxRef() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlutterwavePaymentProviderService service = new FlutterwavePaymentProviderService(builder);

        server.expect(requestTo("https://api.flutterwave.com/v3/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer FLWSECK_TEST-secret"))
                .andExpect(jsonPath("$.tx_ref").value("idem-123"))
                .andExpect(jsonPath("$.amount").value("12.34"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andRespond(withSuccess("""
                        {"status":"success","message":"Hosted Link","data":{"link":"https://checkout.flutterwave.com/v3/hosted/pay/abc"}}
                        """, MediaType.APPLICATION_JSON));

        ChargeResult result = service.charge(chargeRequest(), creds());

        assertThat(result.requiresAction()).isTrue();
        assertThat(result.providerPaymentId()).isEqualTo("idem-123");
        assertThat(result.actionType()).isEqualTo("redirect_url");
        assertThat(result.actionUrl()).isEqualTo("https://checkout.flutterwave.com/v3/hosted/pay/abc");
        server.verify();
    }

    @Test
    void syncStatus_mapsSuccessfulVerificationToSucceeded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlutterwavePaymentProviderService service = new FlutterwavePaymentProviderService(builder);

        server.expect(requestTo(containsString("/v3/transactions/verify_by_reference")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("tx_ref", "idem-123"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"id":42,"tx_ref":"idem-123","status":"successful"}}
                        """, MediaType.APPLICATION_JSON));

        Optional<PaymentIntentStatus> status = service.syncStatus("idem-123", creds());

        assertThat(status).contains(PaymentIntentStatus.SUCCEEDED);
        server.verify();
    }

    @Test
    void refund_verifiesTransactionIdBeforeRefunding() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlutterwavePaymentProviderService service = new FlutterwavePaymentProviderService(builder);

        server.expect(requestTo(containsString("/v3/transactions/verify_by_reference")))
                .andExpect(queryParam("tx_ref", "idem-123"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"id":42,"tx_ref":"idem-123","status":"successful"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.flutterwave.com/v3/transactions/42/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value("5"))
                .andRespond(withSuccess("""
                        {"status":"success","message":"Refund queued","data":{"id":99,"status":"completed"}}
                        """, MediaType.APPLICATION_JSON));

        RefundResult result = service.refund(new RefundRequest(UUID.randomUUID(), "idem-123", 500, "customer_request"), creds());

        assertThat(result.success()).isTrue();
        assertThat(result.providerRefundId()).isEqualTo("99");
        server.verify();
    }

    @Test
    void mapStatus_handlesTerminalStatuses() {
        assertThat(FlutterwavePaymentProviderService.mapStatus("successful")).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(FlutterwavePaymentProviderService.mapStatus("failed")).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(FlutterwavePaymentProviderService.mapStatus("cancelled")).isEqualTo(PaymentIntentStatus.CANCELED);
        assertThat(FlutterwavePaymentProviderService.mapStatus("pending")).isNull();
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

    private static FlutterwaveCredentials creds() {
        return new FlutterwaveCredentials("FLWSECK_TEST-secret", "FLWPUBK_TEST-public", "hash", true);
    }
}
