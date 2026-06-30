package com.masonx.rail.canonical;

public enum RailPaymentStatus {
    APPROVED,           // card auth/sale approved by issuer
    DECLINED,           // card declined by issuer or network
    ACCEPTED,           // bank transfer accepted for processing
    PENDING,            // bank transfer pending settlement
    UNKNOWN,            // ISO 8583 timeout — outcome not known
    REVERSAL_REQUIRED,  // UNKNOWN + reversal task created
    REVERSED,           // reversal confirmed (0410 received)
    SETTLED,            // bank transfer settled (pacs.002 ACSC)
    RETURNED,           // bank transfer returned (pacs.004)
    FAILED              // hard failure (network error, malformed response)
}
