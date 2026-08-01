package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentRequest;

public class NoopGatewayFeeAssessmentPort implements GatewayFeeAssessmentPort {
    @Override
    public void assessPaymentConfirm(PaymentIntent intent, PaymentRequest successfulAttempt) {
        // Test/default port; production wires GatewayFeeAssessmentService.
    }
}
