package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttempt;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptRepository;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptStatus;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Charges an open invoice off-session using the customer's default vaulted PaymentInstrument.
 * Provider calls happen outside DB transactions — only state writes are transactional.
 * Safe to call from the merchant API (S3) and the recurring retry worker (S4).
 */
@Service
public class InvoicePaymentService {

    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentAttemptRepository attemptRepository;
    private final CustomerPaymentMethodRepository paymentMethodRepository;
    private final PaymentInstrumentRepository instrumentRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CredentialsCodec credentialsCodec;
    private final PaymentProviderDispatcher dispatcher;
    private final TransactionTemplate txTemplate;

    public InvoicePaymentService(InvoiceRepository invoiceRepository,
                                 InvoicePaymentAttemptRepository attemptRepository,
                                 CustomerPaymentMethodRepository paymentMethodRepository,
                                 PaymentInstrumentRepository instrumentRepository,
                                 ProviderAccountRepository providerAccountRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 PaymentIntentRepository paymentIntentRepository,
                                 OutboxEventRepository outboxEventRepository,
                                 CredentialsCodec credentialsCodec,
                                 PaymentProviderDispatcher dispatcher,
                                 PlatformTransactionManager txManager) {
        this.invoiceRepository = invoiceRepository;
        this.attemptRepository = attemptRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.instrumentRepository = instrumentRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.credentialsCodec = credentialsCodec;
        this.dispatcher = dispatcher;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Pays an open invoice off-session. Idempotent: returns immediately for already-paid invoices.
     * Must not be called with a DB transaction already open — provider call happens outside any transaction.
     */
    public InvoicePaymentResponse pay(UUID merchantId, UUID invoiceId) {
        // --- Read phase (no transaction needed — reads are consistent) ---
        Invoice invoice = invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            return InvoicePaymentResponse.alreadyPaid(invoice);
        }
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new IllegalStateException("Invoice cannot be paid in status: " + invoice.getStatus());
        }

        CustomerPaymentMethod defaultMethod = paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndDefaultMethodTrueAndStatus(
                        merchantId, invoice.getCustomerId(), CustomerPaymentMethodStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "No active default payment method found for customer"));

        PaymentInstrument instrument = instrumentRepository
                .findByIdAndMerchantId(defaultMethod.getPaymentInstrumentId(), merchantId)
                .orElseThrow(() -> new IllegalStateException("Payment instrument not found"));

        if (instrument.getSource() != InstrumentSource.VAULT_TOKEN
                || instrument.getProviderCustomerReference() == null
                || instrument.getTokenReference() == null) {
            throw new IllegalStateException(
                    "Payment instrument is not set up for off-session charging. "
                    + "Customer must complete a checkout authorization first.");
        }

        ProviderAccount account = providerAccountRepository
                .findById(instrument.getProviderAccountId())
                .orElseThrow(() -> new IllegalStateException("Connector account not found"));

        if (account.getMode() != invoice.getMode()) {
            throw new IllegalStateException(
                    "Connector mode (" + account.getMode() + ") does not match invoice mode ("
                    + invoice.getMode() + ")");
        }

        var credentials = credentialsCodec.decode(account);
        // Deterministic key: same invoice + same attempt number always sends the same key to the provider.
        // Prevents double-charge if the worker retries the same attempt after a crash or timeout.
        int priorAttemptCount = attemptRepository
                .findByMerchantIdAndInvoiceIdOrderByAttemptNumberAsc(merchantId, invoiceId).size();
        String idempotencyKey = "inv-" + invoiceId + "-attempt-" + (priorAttemptCount + 1);

        // --- Transaction A: persist PaymentIntent before the provider call ---
        PaymentIntent savedIntent = txTemplate.execute(ts -> {
            PaymentIntent intent = new PaymentIntent();
            intent.setMerchantId(merchantId);
            intent.setMode(invoice.getMode());
            intent.setAmount(invoice.getAmountDue());
            intent.setCurrency(invoice.getCurrency());
            intent.setIdempotencyKey(idempotencyKey);
            intent.setStatus(PaymentIntentStatus.PROCESSING);
            intent.setCaptureMethod(CaptureMethod.AUTOMATIC);
            intent.setResolvedProvider(instrument.getProvider());
            intent.setConnectorAccountId(account.getId());
            intent.setMetadata("{\"invoiceId\":\"" + invoiceId
                    + "\",\"subscriptionId\":\"" + invoice.getSubscriptionId() + "\"}");
            return paymentIntentRepository.save(intent);
        });

        // --- Provider call (outside any transaction) ---
        ChargeResult result = dispatcher.charge(
                instrument.getProvider(),
                new ChargeRequest(
                        savedIntent.getId(),
                        invoice.getAmountDue(),
                        invoice.getCurrency(),
                        "card",
                        instrument.getTokenReference(),
                        instrument.getProviderCustomerReference(),
                        idempotencyKey,
                        null, null,
                        CaptureMethod.AUTOMATIC,
                        null),
                credentials);

        // requiresAction means 3DS redirect — customer not present, treat as failure
        boolean success = result.success() && !result.requiresAction();
        String failureCode = success ? null
                : result.requiresAction() ? "requires_customer_action"
                : result.failureCode();
        String failureMessage = success ? null
                : result.requiresAction() ? "Customer action required — update payment method"
                : result.failureMessage();

        // --- Transaction B: persist all state changes atomically ---
        int attemptNumber = txTemplate.execute(ts -> {
            // Update intent status
            savedIntent.setStatus(success ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
            savedIntent.setProviderPaymentId(result.providerPaymentId());
            savedIntent.setProviderResponse(result.providerResponseJson());
            paymentIntentRepository.save(savedIntent);

            // Write invoice payment attempt
            List<InvoicePaymentAttempt> prior = attemptRepository
                    .findByMerchantIdAndInvoiceIdOrderByAttemptNumberAsc(merchantId, invoiceId);
            int nextAttempt = prior.size() + 1;

            InvoicePaymentAttempt attempt = new InvoicePaymentAttempt();
            attempt.setMerchantId(merchantId);
            attempt.setMode(invoice.getMode());
            attempt.setInvoiceId(invoiceId);
            attempt.setPaymentIntentId(savedIntent.getId());
            attempt.setAttemptNumber(nextAttempt);
            attempt.setStatus(success
                    ? InvoicePaymentAttemptStatus.SUCCEEDED
                    : InvoicePaymentAttemptStatus.FAILED);
            attempt.setFailureCode(failureCode);
            attempt.setFailureMessage(failureMessage);
            attemptRepository.save(attempt);

            // Update invoice state
            if (success) {
                invoice.setStatus(InvoiceStatus.PAID);
                invoice.setAmountPaid(invoice.getAmountDue());
                invoice.setNextPaymentAttemptAt(null);
            } else {
                invoice.setNextPaymentAttemptAt(Instant.now());
            }
            invoiceRepository.save(invoice);

            // Update subscription state
            subscriptionRepository.findByIdAndMerchantId(invoice.getSubscriptionId(), merchantId)
                    .ifPresent(sub -> {
                        if (success && sub.getStatus() != SubscriptionStatus.ACTIVE) {
                            sub.setStatus(SubscriptionStatus.ACTIVE);
                            subscriptionRepository.save(sub);
                        } else if (!success && sub.getStatus() == SubscriptionStatus.ACTIVE) {
                            sub.setStatus(SubscriptionStatus.PAST_DUE);
                            subscriptionRepository.save(sub);
                        }
                    });

            // Outbox event
            String eventType = success ? "invoice.paid" : "invoice.payment_failed";
            String payload = "{\"invoiceId\":\"" + invoiceId
                    + "\",\"subscriptionId\":\"" + invoice.getSubscriptionId()
                    + "\",\"paymentIntentId\":\"" + savedIntent.getId()
                    + "\",\"attemptNumber\":" + nextAttempt + "}";
            outboxEventRepository.save(new OutboxEvent(merchantId, eventType, invoiceId, payload));

            return nextAttempt;
        });

        return new InvoicePaymentResponse(
                invoiceId,
                invoice.getStatus().name(),
                success ? "ACTIVE" : "PAST_DUE",
                savedIntent.getId(),
                attemptNumber,
                success,
                failureCode,
                failureMessage);
    }

}
