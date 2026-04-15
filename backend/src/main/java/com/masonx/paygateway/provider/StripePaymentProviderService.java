package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.ShippingDetails;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import java.util.Optional;
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
                    "No active Stripe connector found. Add one under Settings → Connectors.",
                    false);
        }

        try {
            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(req.amount())
                    .setCurrency(req.currency().toLowerCase())
                    .setConfirm(true)
                    .setPaymentMethod(req.paymentMethodId())
                    .addPaymentMethodType("card");

            // Manual capture: authorize now, settle later via captureAtProvider()
            if (req.captureMethod() == CaptureMethod.MANUAL) {
                paramsBuilder.setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL);
            }

            // Receipt email
            if (req.billingDetails() != null && req.billingDetails().email() != null) {
                paramsBuilder.setReceiptEmail(req.billingDetails().email());
            }

            // Shipping — used by Stripe Radar and stored as dispute evidence
            PaymentIntentCreateParams.Shipping stripeShipping = buildStripeShipping(req.shippingDetails());
            if (stripeShipping != null) paramsBuilder.setShipping(stripeShipping);

            PaymentIntentCreateParams params = paramsBuilder.build();

            RequestOptions options = RequestOptions.builder()
                    .setApiKey(stripe.secretKey())
                    .setIdempotencyKey(req.idempotencyKey())
                    .build();

            PaymentIntent pi = PaymentIntent.create(params, options);
            // For MANUAL capture, Stripe transitions to "requires_capture" on success
            boolean succeeded = "succeeded".equals(pi.getStatus())
                    || "requires_capture".equals(pi.getStatus());

            String failureCode = succeeded ? null
                    : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getCode() : "unknown";
            return new ChargeResult(
                    succeeded, pi.getId(), pi.toJson(),
                    failureCode,
                    succeeded ? null : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getMessage() : "Payment did not succeed",
                    false   // card-level failures are never retryable with the same card
            );
        } catch (StripeException e) {
            log.error("Stripe charge failed: {} — {}", e.getCode(), e.getMessage());
            // StripeException from the API call itself is a technical/transient failure → retryable
            return new ChargeResult(false, null, null, e.getCode(), e.getMessage(), true);
        }
    }

    private PaymentIntentCreateParams.Shipping buildStripeShipping(ShippingDetails sd) {
        if (sd == null || sd.address() == null) return null;
        PaymentIntentCreateParams.Shipping.Address.Builder addrBuilder =
                PaymentIntentCreateParams.Shipping.Address.builder()
                        .setLine1(sd.address().line1())
                        .setCity(sd.address().city())
                        .setCountry(sd.address().country());
        if (sd.address().line2() != null)       addrBuilder.setLine2(sd.address().line2());
        if (sd.address().state() != null)       addrBuilder.setState(sd.address().state());
        if (sd.address().postalCode() != null)  addrBuilder.setPostalCode(sd.address().postalCode());

        PaymentIntentCreateParams.Shipping.Builder builder =
                PaymentIntentCreateParams.Shipping.builder()
                        .setAddress(addrBuilder.build())
                        .setName(fullName(sd.firstName(), sd.lastName()));
        if (sd.phone() != null) builder.setPhone(sd.phone());
        return builder.build();
    }

    private String fullName(String first, String last) {
        if (first == null && last == null) return "";
        if (first == null) return last;
        if (last == null)  return first;
        return first + " " + last;
    }

    @Override
    public Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe) || stripe.secretKey() == null) return Optional.empty();
        try {
            RequestOptions options = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            PaymentIntent pi = PaymentIntent.retrieve(providerPaymentId, options);
            PaymentIntentStatus mapped = switch (pi.getStatus()) {
                case "succeeded"                -> PaymentIntentStatus.SUCCEEDED;
                case "canceled"                 -> PaymentIntentStatus.CANCELED;
                case "requires_payment_method"  -> {
                    // Stripe sets this after a failed attempt — check lastPaymentError
                    yield pi.getLastPaymentError() != null
                            ? PaymentIntentStatus.FAILED
                            : PaymentIntentStatus.CANCELED;
                }
                default -> null; // still in-flight: processing, requires_action, requires_capture, etc.
            };
            return Optional.ofNullable(mapped);
        } catch (StripeException e) {
            log.warn("Stripe syncStatus failed for {}: {}", providerPaymentId, e.getCode());
            return Optional.empty();
        }
    }

    @Override
    public boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe) || stripe.secretKey() == null) return false;
        try {
            RequestOptions options = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            PaymentIntent pi = PaymentIntent.retrieve(providerPaymentId, options);
            if ("succeeded".equals(pi.getStatus()) || "canceled".equals(pi.getStatus())) return false;
            pi.cancel(options);
            return true;
        } catch (StripeException e) {
            log.warn("Stripe cancelAtProvider failed for {}: {}", providerPaymentId, e.getCode());
            return false;
        }
    }

    @Override
    public boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe) || stripe.secretKey() == null) return false;
        try {
            RequestOptions options = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            PaymentIntent pi = PaymentIntent.retrieve(providerPaymentId, options);
            pi.capture(options);
            return true;
        } catch (StripeException e) {
            log.warn("Stripe captureAtProvider failed for {}: {}", providerPaymentId, e.getCode());
            return false;
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
