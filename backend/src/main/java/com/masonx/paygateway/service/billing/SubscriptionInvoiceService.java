package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptRepository;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.web.dto.InvoiceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionInvoiceService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentAttemptRepository attemptRepository;

    public SubscriptionInvoiceService(SubscriptionRepository subscriptionRepository,
                                      SubscriptionItemRepository itemRepository,
                                      InvoiceRepository invoiceRepository,
                                      InvoicePaymentAttemptRepository attemptRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.itemRepository = itemRepository;
        this.invoiceRepository = invoiceRepository;
        this.attemptRepository = attemptRepository;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> list(UUID merchantId, UUID subscriptionId) {
        loadOwnedSubscription(merchantId, subscriptionId);
        return invoiceRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(merchantId, subscriptionId)
                .stream()
                .map(inv -> InvoiceResponse.from(inv, latestPaymentIntentId(merchantId, inv.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listAll(UUID merchantId, UUID subscriptionId, ApiKeyMode mode) {
        if (subscriptionId != null) {
            loadOwnedSubscription(merchantId, subscriptionId);
            return invoiceRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(merchantId, subscriptionId)
                    .stream()
                    .map(inv -> InvoiceResponse.from(inv, latestPaymentIntentId(merchantId, inv.getId())))
                    .toList();
        }
        return invoiceRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .map(inv -> InvoiceResponse.from(inv, latestPaymentIntentId(merchantId, inv.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID merchantId, UUID invoiceId) {
        Invoice invoice = invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        return InvoiceResponse.from(invoice, latestPaymentIntentId(merchantId, invoiceId));
    }

    private UUID latestPaymentIntentId(UUID merchantId, UUID invoiceId) {
        return attemptRepository
                .findFirstByMerchantIdAndInvoiceIdOrderByAttemptNumberDesc(merchantId, invoiceId)
                .map(a -> a.getPaymentIntentId())
                .orElse(null);
    }

    @Transactional
    public InvoiceResponse generateCurrentPeriodInvoice(UUID merchantId, UUID subscriptionId) {
        Subscription subscription = loadOwnedSubscription(merchantId, subscriptionId);
        validateInvoiceable(subscription);

        return invoiceRepository
                .findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
                        merchantId,
                        subscriptionId,
                        subscription.getCurrentPeriodStart(),
                        subscription.getCurrentPeriodEnd())
                .map(InvoiceResponse::from)
                .orElseGet(() -> InvoiceResponse.from(invoiceRepository.save(buildInvoice(subscription))));
    }

    private Subscription loadOwnedSubscription(UUID merchantId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
    }

    private void validateInvoiceable(Subscription subscription) {
        if (subscription.getCurrentPeriodStart() == null || subscription.getCurrentPeriodEnd() == null) {
            throw new IllegalStateException("Subscription current period is not initialized");
        }
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.PAST_DUE) {
            throw new IllegalStateException("Invoice can only be generated for active or past-due subscriptions");
        }
    }

    private Invoice buildInvoice(Subscription subscription) {
        List<SubscriptionItem> items = itemRepository
                .findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(
                        subscription.getMerchantId(), subscription.getId());
        long amountDue = items.stream()
                .mapToLong(item -> item.getAmount() * item.getQuantity())
                .sum();
        if (amountDue <= 0) {
            throw new IllegalStateException("Subscription has no billable items");
        }

        Instant now = Instant.now();
        Invoice invoice = new Invoice();
        invoice.setMerchantId(subscription.getMerchantId());
        invoice.setCustomerId(subscription.getCustomerId());
        invoice.setSubscriptionId(subscription.getId());
        invoice.setMode(subscription.getMode());
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setAmountDue(amountDue);
        invoice.setAmountPaid(0);
        invoice.setCurrency(subscription.getCurrency());
        invoice.setPeriodStart(subscription.getCurrentPeriodStart());
        invoice.setPeriodEnd(subscription.getCurrentPeriodEnd());
        invoice.setDueAt(now);
        invoice.setNextPaymentAttemptAt(now);
        return invoice;
    }
}
