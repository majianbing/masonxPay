package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StripePaymentProviderService implements PaymentProviderService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProviderService.class);

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public ChargeResult charge(ChargeRequest req, ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe) || stripe.secretKey() == null) {
            return new ChargeResult(false, null, null,
                    "connector_not_configured",
                    "No active Stripe connector found. Add one under Settings → Connectors.");
        }

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(req.amount())
                    .setCurrency(req.currency().toLowerCase())
                    .setConfirm(true)
                    .setPaymentMethod(req.paymentMethodId())
                    .addPaymentMethodType("card")
                    .build();

            RequestOptions options = RequestOptions.builder()
                    .setApiKey(stripe.secretKey())
                    .setIdempotencyKey(req.idempotencyKey())
                    .build();

            PaymentIntent pi = PaymentIntent.create(params, options);
            boolean succeeded = "succeeded".equals(pi.getStatus());

            return new ChargeResult(
                    succeeded, pi.getId(), pi.toJson(),
                    succeeded ? null : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getCode() : "unknown",
                    succeeded ? null : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getMessage() : "Payment did not succeed"
            );
        } catch (StripeException e) {
            log.error("Stripe charge failed: {} — {}", e.getCode(), e.getMessage());
            return new ChargeResult(false, null, null, e.getCode(), e.getMessage());
        }
    }

    @Override
    public RefundResult refund(RefundRequest req, ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe) || stripe.secretKey() == null) {
            return new RefundResult(false, null, "No active Stripe connector found.");
        }

        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(req.providerPaymentId())
                    .setAmount(req.amount())
                    .build();

            RequestOptions options = RequestOptions.builder()
                    .setApiKey(stripe.secretKey())
                    .setIdempotencyKey("refund-" + req.refundId())
                    .build();

            Refund refund = Refund.create(params, options);
            boolean succeeded = "succeeded".equals(refund.getStatus());

            return new RefundResult(
                    succeeded, refund.getId(),
                    succeeded ? null : refund.getFailureReason()
            );
        } catch (StripeException e) {
            log.error("Stripe refund failed: {} — {}", e.getCode(), e.getMessage());
            return new RefundResult(false, null, e.getMessage());
        }
    }
}
