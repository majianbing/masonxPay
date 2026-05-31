package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.Invoice;

import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID customerId,
        UUID subscriptionId,
        String mode,
        String status,
        long amountDue,
        long amountPaid,
        String currency,
        Instant periodStart,
        Instant periodEnd,
        Instant dueAt,
        Instant nextPaymentAttemptAt,
        Instant createdAt
) {
    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getCustomerId(),
                invoice.getSubscriptionId(),
                invoice.getMode() != null ? invoice.getMode().name() : null,
                invoice.getStatus().name(),
                invoice.getAmountDue(),
                invoice.getAmountPaid(),
                invoice.getCurrency(),
                invoice.getPeriodStart(),
                invoice.getPeriodEnd(),
                invoice.getDueAt(),
                invoice.getNextPaymentAttemptAt(),
                invoice.getCreatedAt());
    }
}
