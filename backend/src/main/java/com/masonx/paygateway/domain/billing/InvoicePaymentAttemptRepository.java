package com.masonx.paygateway.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoicePaymentAttemptRepository extends JpaRepository<InvoicePaymentAttempt, UUID> {
    List<InvoicePaymentAttempt> findByMerchantIdAndInvoiceIdOrderByAttemptNumberAsc(UUID merchantId, UUID invoiceId);
    java.util.Optional<InvoicePaymentAttempt> findFirstByMerchantIdAndInvoiceIdOrderByAttemptNumberDesc(UUID merchantId, UUID invoiceId);
}
