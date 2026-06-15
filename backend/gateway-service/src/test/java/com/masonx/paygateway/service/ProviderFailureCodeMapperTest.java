package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFailureCodeMapperTest {

    private final ProviderFailureCodeMapper mapper = new ProviderFailureCodeMapper();

    @Test
    void stripeKnownCodesMapToCorrectCategories() {
        assertThat(mapper.category(PaymentProvider.STRIPE, "card_declined")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.STRIPE, "fraudulent")).isEqualTo("RISK_DECLINE");
        assertThat(mapper.category(PaymentProvider.STRIPE, "lost_card")).isEqualTo("RISK_DECLINE");
        assertThat(mapper.category(PaymentProvider.STRIPE, "stolen_card")).isEqualTo("RISK_DECLINE");
        assertThat(mapper.category(PaymentProvider.STRIPE, "insufficient_funds")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(mapper.category(PaymentProvider.STRIPE, "expired_card")).isEqualTo("INVALID_PAYMENT_METHOD");
        assertThat(mapper.category(PaymentProvider.STRIPE, "incorrect_cvc")).isEqualTo("INVALID_PAYMENT_METHOD");
        assertThat(mapper.category(PaymentProvider.STRIPE, "authentication_required")).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(mapper.category(PaymentProvider.STRIPE, "rate_limit")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(mapper.category(PaymentProvider.STRIPE, "service_unavailable")).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void squareKnownCodesMapToCorrectCategories() {
        assertThat(mapper.category(PaymentProvider.SQUARE, "card_declined")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.SQUARE, "insufficient_funds")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(mapper.category(PaymentProvider.SQUARE, "card_expired")).isEqualTo("INVALID_PAYMENT_METHOD");
        assertThat(mapper.category(PaymentProvider.SQUARE, "cvv_failure")).isEqualTo("INVALID_PAYMENT_METHOD");
        assertThat(mapper.category(PaymentProvider.SQUARE, "gateway_timeout")).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(mapper.category(PaymentProvider.SQUARE, "authorization_error")).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(mapper.category(PaymentProvider.SQUARE, "fraud_risk")).isEqualTo("RISK_DECLINE");
    }

    @Test
    void braintreeKnownCodesMapToCorrectCategories() {
        assertThat(mapper.category(PaymentProvider.BRAINTREE, "processor_declined")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.BRAINTREE, "gateway_rejected")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(mapper.category(PaymentProvider.BRAINTREE, "settlement_declined")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.BRAINTREE, "validation_error")).isEqualTo("INVALID_PAYMENT_METHOD");
    }

    @Test
    void mollieKnownCodesMapToCorrectCategories() {
        assertThat(mapper.category(PaymentProvider.MOLLIE, "authorization_failed")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.MOLLIE, "card_expired")).isEqualTo("INVALID_PAYMENT_METHOD");
        assertThat(mapper.category(PaymentProvider.MOLLIE, "insufficient_funds")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(mapper.category(PaymentProvider.MOLLIE, "fraud_suspected")).isEqualTo("RISK_DECLINE");
    }

    @Test
    void simulatorKnownCodesMapToCorrectCategories() {
        assertThat(mapper.category(PaymentProvider.SIMULATOR, "simulator_declined")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.SIMULATOR, "simulator_timeout")).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(mapper.category(PaymentProvider.SIMULATOR, "simulator_setup_failed")).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void commonCodesMapRegardlessOfProvider() {
        assertThat(mapper.category(PaymentProvider.STRIPE, "gateway_error")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(mapper.category(PaymentProvider.SQUARE, "gateway_error")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(mapper.category(null, "connector_not_configured")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(mapper.category(null, "provider_exception")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(mapper.category(null, "unexpected_response")).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void unknownCodesReturnNull() {
        assertThat(mapper.category(PaymentProvider.STRIPE, "some_future_code")).isNull();
        assertThat(mapper.category(PaymentProvider.SQUARE, "totally_unknown")).isNull();
        assertThat(mapper.category(null, "unknown_code")).isNull();
    }

    @Test
    void nullOrBlankCodeReturnsNull() {
        assertThat(mapper.category(PaymentProvider.STRIPE, null)).isNull();
        assertThat(mapper.category(PaymentProvider.STRIPE, "")).isNull();
        assertThat(mapper.category(PaymentProvider.STRIPE, "  ")).isNull();
    }

    @Test
    void codeNormalizationHandlesCaseAndWhitespace() {
        assertThat(mapper.category(PaymentProvider.STRIPE, "CARD_DECLINED")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.STRIPE, "  card_declined  ")).isEqualTo("HARD_DECLINE");
        assertThat(mapper.category(PaymentProvider.SQUARE, "INSUFFICIENT_FUNDS")).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
