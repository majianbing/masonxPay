package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoiceStatus;

import java.util.UUID;

public record InvoicePaymentResponse(
        UUID invoiceId,
        String invoiceStatus,
        String subscriptionStatus,
        UUID paymentIntentId,
        int attemptNumber,
        boolean success,
        String failureCode,
        String failureMessage
) {
    public static InvoicePaymentResponse alreadyPaid(Invoice invoice) {
        return new InvoicePaymentResponse(
                invoice.getId(),
                InvoiceStatus.PAID.name(),
                "ACTIVE",
                null,
                0,
                true,
                null,
                null);
    }
}
