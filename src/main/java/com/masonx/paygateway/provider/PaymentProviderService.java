package com.masonx.paygateway.provider;

public interface PaymentProviderService {
    ChargeResult charge(ChargeRequest request);
    RefundResult refund(RefundRequest request);
}
