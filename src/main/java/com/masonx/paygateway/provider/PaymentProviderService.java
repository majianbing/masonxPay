package com.masonx.paygateway.provider;

public interface PaymentProviderService {
    ChargeResult charge(ChargeRequest request);
}
