package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionInvoiceServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionItemRepository itemRepository;
    private InvoiceRepository invoiceRepository;
    private SubscriptionInvoiceService service;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        itemRepository = mock(SubscriptionItemRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        service = new SubscriptionInvoiceService(subscriptionRepository, itemRepository, invoiceRepository);
    }

    @Test
    void generateCurrentPeriodInvoiceUsesSubscriptionItemsAndIsIdempotent() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Instant periodStart = Instant.parse("2026-05-01T00:00:00Z");
        Instant periodEnd = Instant.parse("2026-06-01T00:00:00Z");
        Subscription subscription = subscription(merchantId, customerId, subscriptionId, SubscriptionStatus.ACTIVE, periodStart, periodEnd);
        Invoice existing = invoice(merchantId, customerId, subscriptionId, periodStart, periodEnd, 5800);

        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(subscription));
        when(invoiceRepository.findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
                merchantId, subscriptionId, periodStart, periodEnd))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(item(merchantId, subscriptionId, 2900, 2)));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            ReflectionTestUtils.setField(invoice, "id", UUID.randomUUID());
            return invoice;
        });

        var created = service.generateCurrentPeriodInvoice(merchantId, subscriptionId);
        var repeated = service.generateCurrentPeriodInvoice(merchantId, subscriptionId);

        assertThat(created.mode()).isEqualTo(ApiKeyMode.TEST.name());
        assertThat(created.status()).isEqualTo(InvoiceStatus.OPEN.name());
        assertThat(created.amountDue()).isEqualTo(5800);
        assertThat(created.amountPaid()).isZero();
        assertThat(created.currency()).isEqualTo("usd");
        assertThat(created.periodStart()).isEqualTo(periodStart);
        assertThat(created.periodEnd()).isEqualTo(periodEnd);
        assertThat(repeated.id()).isEqualTo(existing.getId());
        verify(invoiceRepository).save(any(Invoice.class));
    }

    @Test
    void generateCurrentPeriodInvoiceRejectsIncompleteSubscription() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = subscription(
                merchantId,
                UUID.randomUUID(),
                subscriptionId,
                SubscriptionStatus.INCOMPLETE,
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"));
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.generateCurrentPeriodInvoice(merchantId, subscriptionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active or past-due");
        verify(invoiceRepository, never()).save(any());
    }

    private Subscription subscription(UUID merchantId,
                                      UUID customerId,
                                      UUID subscriptionId,
                                      SubscriptionStatus status,
                                      Instant periodStart,
                                      Instant periodEnd) {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setMerchantId(merchantId);
        subscription.setCustomerId(customerId);
        subscription.setMode(ApiKeyMode.TEST);
        subscription.setStatus(status);
        subscription.setCurrency("usd");
        subscription.setIntervalUnit(BillingIntervalUnit.MONTH);
        subscription.setIntervalCount(1);
        subscription.setCurrentPeriodStart(periodStart);
        subscription.setCurrentPeriodEnd(periodEnd);
        return subscription;
    }

    private SubscriptionItem item(UUID merchantId, UUID subscriptionId, long amount, int quantity) {
        SubscriptionItem item = new SubscriptionItem();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        item.setMerchantId(merchantId);
        item.setSubscriptionId(subscriptionId);
        item.setDescription("Pro plan");
        item.setAmount(amount);
        item.setQuantity(quantity);
        return item;
    }

    private Invoice invoice(UUID merchantId,
                            UUID customerId,
                            UUID subscriptionId,
                            Instant periodStart,
                            Instant periodEnd,
                            long amountDue) {
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", UUID.randomUUID());
        invoice.setMerchantId(merchantId);
        invoice.setCustomerId(customerId);
        invoice.setSubscriptionId(subscriptionId);
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setAmountDue(amountDue);
        invoice.setAmountPaid(0);
        invoice.setCurrency("usd");
        invoice.setPeriodStart(periodStart);
        invoice.setPeriodEnd(periodEnd);
        invoice.setDueAt(Instant.now());
        invoice.setNextPaymentAttemptAt(Instant.now());
        return invoice;
    }
}
