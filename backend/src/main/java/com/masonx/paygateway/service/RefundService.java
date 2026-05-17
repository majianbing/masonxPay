package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import com.masonx.paygateway.web.dto.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final PaymentProviderDispatcher dispatcher;
    private final ProviderAccountService providerAccountService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final PaymentMetrics metrics;

    public RefundService(PaymentIntentRepository paymentIntentRepository,
                         RefundRepository refundRepository,
                         PaymentProviderDispatcher dispatcher,
                         ProviderAccountService providerAccountService,
                         OutboxEventRepository outboxEventRepository,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager txManager,
                         PaymentMetrics metrics) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRepository = refundRepository;
        this.dispatcher = dispatcher;
        this.providerAccountService = providerAccountService;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(txManager);
        this.metrics = metrics;
    }

    /**
     * No class-level @Transactional — the remote refund call must not hold a DB connection.
     * TX 1: validate + create PENDING refund row
     * Remote call (outside TX)
     * TX 2: update refund with outcome
     */
    public RefundResponse createRefund(UUID merchantId, UUID paymentIntentId, CreateRefundRequest req) {
        // TX 1: validate intent, create PENDING refund, resolve credentials
        record RefundSetup(Refund refund, PaymentIntent intent, ProviderCredentials creds) {}

        RefundSetup setup = txTemplate.execute(ts -> {
            PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(paymentIntentId, merchantId)
                    .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));

            if (intent.getStatus() != PaymentIntentStatus.SUCCEEDED) {
                throw new IllegalStateException("Can only refund SUCCEEDED payment intents");
            }

            long refundAmount = req.amount() != null ? req.amount() : intent.getAmount();
            if (refundAmount <= 0) {
                throw new IllegalArgumentException("Refund amount must be positive");
            }

            long alreadyRefunded = refundRepository.sumActiveByPaymentIntentId(paymentIntentId);
            long availableRefundAmount = intent.getAmount() - alreadyRefunded;
            if (refundAmount > availableRefundAmount) {
                throw new IllegalArgumentException(
                        "Refund amount exceeds available refundable amount " +
                        "(original: " + intent.getAmount() +
                        ", already refunded: " + alreadyRefunded +
                        ", available: " + availableRefundAmount +
                        ", requested: " + refundAmount + ")");
            }

            Refund refund = new Refund();
            refund.setPaymentIntentId(paymentIntentId);
            refund.setMerchantId(merchantId);
            refund.setMode(intent.getMode());
            refund.setAmount(refundAmount);
            refund.setCurrency(intent.getCurrency());
            refund.setStatus(RefundStatus.PENDING);
            if (req.reason() != null) {
                try {
                    refund.setReason(RefundReason.valueOf(req.reason().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    refund.setReason(RefundReason.CUSTOMER_REQUEST);
                }
            }
            refund = refundRepository.save(refund);

            ProviderCredentials creds = intent.getConnectorAccountId() != null
                    ? providerAccountService.loadCredentials(intent.getConnectorAccountId())
                    : providerAccountService.resolveCredentials(
                            merchantId, intent.getResolvedProvider(), intent.getMode());

            return new RefundSetup(refund, intent, creds);
        });

        // Record metric before the remote call (intent is already validated at this point)
        metrics.recordRefundInitiated(setup.intent().getResolvedProvider() != null
                ? setup.intent().getResolvedProvider().name() : null);

        // Remote call — intentionally outside any transaction
        RefundResult result = dispatcher.refund(
                setup.intent().getResolvedProvider(),
                new RefundRequest(setup.refund().getId(), setup.intent().getProviderPaymentId(),
                        setup.refund().getAmount(), req.reason()),
                setup.creds());

        // TX 2: persist the outcome
        final RefundResult r = result;
        return txTemplate.execute(ts -> {
            Refund refund = refundRepository.findById(setup.refund().getId()).orElseThrow();
            refund.setStatus(r.success() ? RefundStatus.SUCCEEDED : RefundStatus.FAILED);
            refund.setProviderRefundId(r.providerRefundId());
            refund.setFailureReason(r.failureReason());
            refund = refundRepository.save(refund);
            RefundResponse response = RefundResponse.from(refund);
            writeOutboxEvent(refund.getMerchantId(),
                    r.success() ? "refund.succeeded" : "refund.failed",
                    refund.getId(),
                    response);
            return response;
        });
    }

    private void writeOutboxEvent(UUID merchantId, String eventType, UUID resourceId,
                                  RefundResponse payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(new OutboxEvent(merchantId, eventType, resourceId, json));
        } catch (JsonProcessingException e) {
            // Refund outcome is already persisted. Do not roll back the financial state because
            // a webhook/search side-effect payload could not be serialized.
            log.warn("Failed to serialize outbox payload for event {} on refund {}: {}",
                    eventType, resourceId, e.getMessage());
        }
    }
}
