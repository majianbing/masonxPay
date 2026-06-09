package com.masonx.paygateway.domain.dispute;

public enum DisputeStatus {
    NEEDS_RESPONSE,
    UNDER_REVIEW,
    WON,
    LOST,
    CHARGE_REFUNDED,
    WARNING_NEEDS_RESPONSE,
    WARNING_UNDER_REVIEW,
    WARNING_CLOSED
}
