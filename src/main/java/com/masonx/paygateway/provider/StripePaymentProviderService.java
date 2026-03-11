package com.masonx.paygateway.provider;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service("stripePaymentProviderService")
public class StripePaymentProviderService implements PaymentProviderService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProviderService.class);

    @Value("${app.stripe.secret-key:}")
    private String secretKey;

    @PostConstruct
    public void init() {
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe initialized (key prefix: {})", secretKey.substring(0, Math.min(8, secretKey.length())));
        } else {
            log.warn("STRIPE_SECRET_KEY not set — Stripe charges will fail");
        }
    }

    @Override
    public ChargeResult charge(ChargeRequest req) {
        if (secretKey == null || secretKey.isBlank()) {
            return new ChargeResult(false, null, null,
                    "stripe_not_configured", "STRIPE_SECRET_KEY is not set");
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
                    .setIdempotencyKey(req.idempotencyKey())
                    .build();

            PaymentIntent pi = PaymentIntent.create(params, options);
            boolean succeeded = "succeeded".equals(pi.getStatus());

            return new ChargeResult(
                    succeeded,
                    pi.getId(),
                    pi.toJson(),
                    succeeded ? null : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getCode() : "unknown",
                    succeeded ? null : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getMessage() : "Payment did not succeed"
            );
        } catch (StripeException e) {
            log.error("Stripe charge failed: {} — {}", e.getCode(), e.getMessage());
            return new ChargeResult(false, null, null, e.getCode(), e.getMessage());
        }
    }
}
