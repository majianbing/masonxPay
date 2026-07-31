package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentRequest;

public interface GatewayFeeAssessmentPort {
    void assessPaymentConfirm(PaymentIntent intent, PaymentRequest successfulAttempt);
}
