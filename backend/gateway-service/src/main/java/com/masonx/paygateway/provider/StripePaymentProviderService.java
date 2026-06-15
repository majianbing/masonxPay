package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.ShippingDetails;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.RefundCreateParams;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StripePaymentProviderService
        extends AbstractPaymentProviderService<StripeCredentials>
        implements ReusablePaymentMethodProviderService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProviderService.class);

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.STRIPE;
    }

    @Override
    protected Class<StripeCredentials> credentialsType() {
        return StripeCredentials.class;
    }

    @Override
    protected ChargeResult sendCharge(ChargeRequest req, StripeCredentials stripe) {
        if (stripe.secretKey() == null) return missingConnectorCharge();

        try {
            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(req.amount())
                    .setCurrency(req.currency().toLowerCase())
                    .setConfirm(true)
                    .setPaymentMethod(req.paymentMethodId())
                    .addPaymentMethodType("card");

            if (req.providerCustomerReference() != null && !req.providerCustomerReference().isBlank()) {
                paramsBuilder.setCustomer(req.providerCustomerReference());
                paramsBuilder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
            }

            if (req.returnUrl() != null) {
                paramsBuilder.setReturnUrl(req.returnUrl());
            }

            if (req.captureMethod() == CaptureMethod.MANUAL) {
                paramsBuilder.setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL);
            }

            if (req.billingDetails() != null && req.billingDetails().email() != null) {
                paramsBuilder.setReceiptEmail(req.billingDetails().email());
            }

            PaymentIntentCreateParams.Shipping stripeShipping = buildStripeShipping(req.shippingDetails());
            if (stripeShipping != null) paramsBuilder.setShipping(stripeShipping);

            RequestOptions options = RequestOptions.builder()
                    .setApiKey(stripe.secretKey())
                    .setIdempotencyKey(req.idempotencyKey())
                    .build();

            PaymentIntent pi = PaymentIntent.create(paramsBuilder.build(), options);

            if ("requires_action".equals(pi.getStatus()) && pi.getNextAction() != null) {
                String nextActionType = pi.getNextAction().getType();
                if ("use_stripe_sdk".equals(nextActionType)) {
                    return ChargeResult.actionRequired(pi.getId(), pi.toJson(),
                            "stripe_sdk", null, pi.getClientSecret());
                } else {
                    String redirectUrl = pi.getNextAction().getRedirectToUrl() != null
                            ? pi.getNextAction().getRedirectToUrl().getUrl() : null;
                    return ChargeResult.actionRequired(pi.getId(), pi.toJson(),
                            "redirect_url", redirectUrl, null);
                }
            }

            boolean succeeded = "succeeded".equals(pi.getStatus())
                    || "requires_capture".equals(pi.getStatus());

            String failureCode = succeeded ? null
                    : pi.getLastPaymentError() != null ? pi.getLastPaymentError().getCode() : "unknown";
            return new ChargeResult(
                    succeeded, pi.getId(), pi.toJson(),
                    failureCode,
                    succeeded ? null : pi.getLastPaymentError() != null
                            ? pi.getLastPaymentError().getMessage() : "Payment did not succeed",
                    false, false, null, null, null
            );
        } catch (StripeException e) {
            log.error("Stripe charge failed: {} — {}", e.getCode(), e.getMessage());
            return new ChargeResult(false, null, null, e.getCode(), e.getMessage(), true, false, null, null, null);
        }
    }

    @Override
    protected RefundResult sendRefund(RefundRequest req, StripeCredentials stripe) {
        if (stripe.secretKey() == null) return missingConnectorRefund();

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
            return new RefundResult(succeeded, refund.getId(),
                    succeeded ? null : refund.getFailureReason());
        } catch (StripeException e) {
            log.error("Stripe refund failed: {} — {}", e.getCode(), e.getMessage());
            return new RefundResult(false, null, e.getMessage());
        }
    }

    @Override
    protected Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, StripeCredentials stripe) {
        if (stripe.secretKey() == null) return Optional.empty();
        try {
            RequestOptions options = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            PaymentIntent pi = PaymentIntent.retrieve(providerPaymentId, options);
            PaymentIntentStatus mapped = switch (pi.getStatus()) {
                case "succeeded"               -> PaymentIntentStatus.SUCCEEDED;
                case "canceled"                -> PaymentIntentStatus.CANCELED;
                case "requires_payment_method" -> pi.getLastPaymentError() != null
                        ? PaymentIntentStatus.FAILED : PaymentIntentStatus.CANCELED;
                default                        -> null;
            };
            return Optional.ofNullable(mapped);
        } catch (StripeException e) {
            log.warn("Stripe syncStatus failed for {}: {}", providerPaymentId, e.getCode());
            return Optional.empty();
        }
    }

    @Override
    protected boolean sendCapture(String providerPaymentId, StripeCredentials stripe) {
        if (stripe.secretKey() == null) return false;
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
    protected boolean sendCancel(String providerPaymentId, StripeCredentials stripe) {
        if (stripe.secretKey() == null) return false;
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

    // ── ReusablePaymentMethodProviderService ──────────────────────────────────

    @Override
    public ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request,
            com.masonx.paygateway.provider.credentials.ProviderCredentials creds) {
        if (!(creds instanceof StripeCredentials stripe) || stripe.secretKey() == null) {
            return ReusablePaymentMethodSetupResult.failed(
                    "connector_not_configured",
                    "No active Stripe connector found. Add one under Settings → Connectors.",
                    false);
        }
        if (request.providerPaymentMethodId() == null || request.providerPaymentMethodId().isBlank()) {
            return ReusablePaymentMethodSetupResult.failed(
                    "missing_payment_method",
                    "Stripe reusable setup requires a PaymentMethod id from Stripe.js.",
                    false);
        }

        try {
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(stripe.secretKey())
                    .setIdempotencyKey(request.idempotencyKey())
                    .build();
            String customerId = request.existingProviderCustomerReference();
            if (customerId == null || customerId.isBlank()) {
                CustomerCreateParams.Builder customerParams = CustomerCreateParams.builder()
                        .putMetadata("masonxpayCustomerId", request.customerId().toString());
                if (request.billingDetails() != null) {
                    if (request.billingDetails().email() != null)
                        customerParams.setEmail(request.billingDetails().email());
                    if (request.billingDetails().phone() != null)
                        customerParams.setPhone(request.billingDetails().phone());
                    String name = fullName(request.billingDetails().firstName(), request.billingDetails().lastName());
                    if (!name.isBlank()) customerParams.setName(name);
                }
                customerId = Customer.create(customerParams.build(), options).getId();
            }

            PaymentMethod paymentMethod = PaymentMethod.retrieve(request.providerPaymentMethodId(),
                    RequestOptions.builder().setApiKey(stripe.secretKey()).build());
            if (paymentMethod.getCustomer() == null || paymentMethod.getCustomer().isBlank()) {
                paymentMethod = paymentMethod.attach(
                        PaymentMethodAttachParams.builder().setCustomer(customerId).build(),
                        RequestOptions.builder().setApiKey(stripe.secretKey()).build());
            }
            return ReusablePaymentMethodSetupResult.succeeded(customerId, paymentMethod.getId(), paymentMethod.toJson());
        } catch (StripeException e) {
            log.error("Stripe reusable payment method setup failed: {} — {}", e.getCode(), e.getMessage());
            return ReusablePaymentMethodSetupResult.failed(e.getCode(), e.getMessage(), true);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private PaymentIntentCreateParams.Shipping buildStripeShipping(ShippingDetails sd) {
        if (sd == null || sd.address() == null) return null;
        PaymentIntentCreateParams.Shipping.Address.Builder addrBuilder =
                PaymentIntentCreateParams.Shipping.Address.builder()
                        .setLine1(sd.address().line1())
                        .setCity(sd.address().city())
                        .setCountry(sd.address().country());
        if (sd.address().line2() != null)      addrBuilder.setLine2(sd.address().line2());
        if (sd.address().state() != null)      addrBuilder.setState(sd.address().state());
        if (sd.address().postalCode() != null) addrBuilder.setPostalCode(sd.address().postalCode());

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
        if (last == null) return first;
        return first + " " + last;
    }
}
