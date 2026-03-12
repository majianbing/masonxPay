package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import com.masonx.paygateway.provider.StripePaymentProviderService;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import com.masonx.paygateway.web.dto.RefundResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class RefundService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;
    private final StripePaymentProviderService stripeProvider;

    public RefundService(PaymentIntentRepository paymentIntentRepository,
                         RefundRepository refundRepository,
                         StripePaymentProviderService stripeProvider) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRepository = refundRepository;
        this.stripeProvider = stripeProvider;
    }

    public RefundResponse createRefund(UUID merchantId, UUID paymentIntentId, CreateRefundRequest req) {
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

        RefundResult result = stripeProvider.refund(new RefundRequest(
                refund.getId(),
                intent.getProviderPaymentId(),
                refundAmount,
                req.reason()
        ));

        refund.setStatus(result.success() ? RefundStatus.SUCCEEDED : RefundStatus.FAILED);
        refund.setProviderRefundId(result.providerRefundId());
        refund.setFailureReason(result.failureReason());
        refund = refundRepository.save(refund);

        return RefundResponse.from(refund);
    }
}
