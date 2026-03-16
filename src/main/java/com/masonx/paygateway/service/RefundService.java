package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import com.masonx.paygateway.web.dto.RefundResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class RefundService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final PaymentProviderDispatcher dispatcher;
    private final ProviderAccountService providerAccountService;
    private final TransactionTemplate txTemplate;

    public RefundService(PaymentIntentRepository paymentIntentRepository,
                         RefundRepository refundRepository,
                         PaymentProviderDispatcher dispatcher,
                         ProviderAccountService providerAccountService,
                         PlatformTransactionManager txManager) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRepository = refundRepository;
        this.dispatcher = dispatcher;
        this.providerAccountService = providerAccountService;
        this.txTemplate = new TransactionTemplate(txManager);
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
            PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantId(paymentIntentId, merchantId)
                    .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));

            if (intent.getStatus() != PaymentIntentStatus.SUCCEEDED) {
                throw new IllegalStateException("Can only refund SUCCEEDED payment intents");
            }

            long refundAmount = req.amount() != null ? req.amount() : intent.getAmount();
            if (refundAmount > intent.getAmount()) {
                throw new IllegalArgumentException("Refund amount exceeds original payment amount");
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
            return RefundResponse.from(refundRepository.save(refund));
        });
    }
}
