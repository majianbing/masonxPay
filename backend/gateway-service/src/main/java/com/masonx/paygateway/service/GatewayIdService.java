package com.masonx.paygateway.service;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.dispute.Dispute;
import com.masonx.paygateway.domain.dispute.DisputeEvidenceFile;
import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.Refund;
import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.webhook.GatewayEvent;
import com.masonx.paygateway.domain.webhook.WebhookDelivery;
import com.masonx.paygateway.domain.webhook.WebhookEndpoint;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class GatewayIdService {

    private final SnowflakeIdGenerator idGenerator;

    public GatewayIdService(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    public String generate(MasonXIdPrefix prefix) {
        return idGenerator.generate(Objects.requireNonNull(prefix, "prefix").prefix());
    }

    public void assignPaymentIntent(PaymentIntent intent) {
        assign(intent.getExternalId(), generated -> intent.setExternalId(generated), MasonXIdPrefix.PAYMENT_INTENT);
    }

    public void assignPaymentRequest(PaymentRequest request) {
        assign(request.getExternalId(), generated -> request.setExternalId(generated), MasonXIdPrefix.PAYMENT_REQUEST);
    }

    public void assignRefund(Refund refund) {
        assign(refund.getExternalId(), generated -> refund.setExternalId(generated), MasonXIdPrefix.REFUND);
    }

    public void assignMerchant(Merchant merchant) {
        assign(merchant.getExternalId(), generated -> merchant.setExternalId(generated), MasonXIdPrefix.MERCHANT);
    }

    public void assignBillingCustomer(BillingCustomer customer) {
        assign(customer.getExternalId(), generated -> customer.setExternalId(generated), MasonXIdPrefix.BILLING_CUSTOMER);
    }

    public void assignSubscription(Subscription subscription) {
        assign(subscription.getExternalId(), generated -> subscription.setExternalId(generated), MasonXIdPrefix.SUBSCRIPTION);
    }

    public void assignInvoice(Invoice invoice) {
        assign(invoice.getExternalId(), generated -> invoice.setExternalId(generated), MasonXIdPrefix.INVOICE);
    }

    public void assignDispute(Dispute dispute) {
        assign(dispute.getExternalId(), generated -> dispute.setExternalId(generated), MasonXIdPrefix.DISPUTE);
    }

    public void assignDisputeEvidenceFile(DisputeEvidenceFile file) {
        assign(file.getExternalId(), generated -> file.setExternalId(generated), MasonXIdPrefix.DISPUTE_EVIDENCE_FILE);
    }

    public void assignScheduledRetryJob(ScheduledRetryJob job) {
        assign(job.getExternalId(), generated -> job.setExternalId(generated), MasonXIdPrefix.SCHEDULED_RETRY_JOB);
    }

    public void assignOutboxEvent(OutboxEvent event) {
        assign(event.getExternalId(), generated -> event.setExternalId(generated), MasonXIdPrefix.EVENT);
    }

    public void assignGatewayEvent(GatewayEvent event) {
        assign(event.getExternalId(), generated -> event.setExternalId(generated), MasonXIdPrefix.EVENT);
    }

    public void assignWebhookDelivery(WebhookDelivery delivery) {
        assign(delivery.getExternalId(), generated -> delivery.setExternalId(generated), MasonXIdPrefix.WEBHOOK_DELIVERY);
    }

    public void assignWebhookEndpoint(WebhookEndpoint endpoint) {
        assign(endpoint.getExternalId(), generated -> endpoint.setExternalId(generated), MasonXIdPrefix.WEBHOOK_ENDPOINT);
    }

    public OutboxEvent outboxEvent(UUID merchantId, String eventType, UUID resourceId, String payload) {
        OutboxEvent event = new OutboxEvent(merchantId, eventType, resourceId, payload);
        assignOutboxEvent(event);
        return event;
    }

    private void assign(String existingValue, java.util.function.Consumer<String> setter, MasonXIdPrefix prefix) {
        if (existingValue == null || existingValue.isBlank()) {
            setter.accept(generate(prefix));
        }
    }
}
