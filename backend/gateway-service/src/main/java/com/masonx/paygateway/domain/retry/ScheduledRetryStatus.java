package com.masonx.paygateway.domain.retry;

public enum ScheduledRetryStatus {
    SCHEDULED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
