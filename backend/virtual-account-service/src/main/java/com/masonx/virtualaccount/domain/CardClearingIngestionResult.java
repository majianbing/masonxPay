package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;

public record CardClearingIngestionResult(
        boolean posted,
        SettlementExceptionReason parkReason,
        String parkDetail
) {

    public static CardClearingIngestionResult matched() {
        return new CardClearingIngestionResult(true, null, null);
    }

    public static CardClearingIngestionResult duplicate() {
        return new CardClearingIngestionResult(false, null, null);
    }

    public static CardClearingIngestionResult park(SettlementExceptionReason reason, String detail) {
        return new CardClearingIngestionResult(false, reason, detail);
    }

    public boolean shouldPark() {
        return parkReason != null;
    }
}
