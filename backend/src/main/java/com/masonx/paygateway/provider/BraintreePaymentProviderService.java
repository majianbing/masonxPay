package com.masonx.paygateway.provider;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.CustomerRequest;
import com.braintreegateway.Environment;
import com.braintreegateway.Result;
import com.braintreegateway.Transaction;
import com.braintreegateway.TransactionRequest;
import com.masonx.paygateway.domain.payment.Address;
import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.ShippingDetails;
import com.masonx.paygateway.provider.credentials.BraintreeCredentials;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;

import java.util.Optional;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Braintree payment provider — uses the official Braintree Java SDK.
 *
 * Payment flow:
 *   1. Frontend calls POST /api/v1/merchants/{id}/connectors/{accountId}/client-token
 *      to get a short-lived client token.
 *   2. Frontend initialises Braintree Drop-in UI with the token; user completes payment
 *      and receives a payment method nonce.
 *   3. Frontend passes nonce to its backend, which submits it as paymentMethodId in
 *      POST /api/v1/payment-intents/{id}/confirm.
 *
 * Idempotency: Braintree has no native idempotency key header. We store our internal
 * idempotency key in the transaction's orderId field. Braintree does not reject duplicates
 * automatically based on orderId — duplicate protection is still enforced by our own DB
 * unique constraint on (merchant_id, idempotency_key).
 *
 * Amount: stored in the gateway as minor currency units (cents). Braintree expects a
 * BigDecimal in major units (dollars), so we shift the decimal two places left.
 */
@Service
public class BraintreePaymentProviderService implements PaymentProviderService {

    private static final Logger log = LoggerFactory.getLogger(BraintreePaymentProviderService.class);

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.BRAINTREE;
    }

    @Override
    public ChargeResult charge(ChargeRequest req, ProviderCredentials creds) {
        if (!(creds instanceof BraintreeCredentials bt) || bt.privateKey() == null) {
            return new ChargeResult(false, null, null,
                    "connector_not_configured",
                    "No active Braintree connector found. Add one under Settings → Connectors.",
                    false, false, null, null, null);
        }

        BraintreeGateway gateway = buildGateway(bt);
        try {
            // Amount: convert cents → dollars (Braintree requires decimal dollars)
            BigDecimal amount = new BigDecimal(req.amount()).movePointLeft(2);

            TransactionRequest transactionRequest = new TransactionRequest()
                    .amount(amount)
                    .paymentMethodNonce(req.paymentMethodId())  // nonce from Drop-in UI
                    .orderId(req.idempotencyKey());             // stored for our audit trail

            // Customer details — used by Braintree for risk scoring and receipts
            if (req.billingDetails() != null) {
                BillingDetails bd = req.billingDetails();
                CustomerRequest customer = transactionRequest.customer();
                if (bd.firstName() != null) customer.firstName(bd.firstName());
                if (bd.lastName() != null)  customer.lastName(bd.lastName());
                if (bd.email() != null)     customer.email(bd.email());
                if (bd.phone() != null)     customer.phone(bd.phone());
                customer.done();

                // Billing address — used for AVS verification
                if (bd.address() != null) {
                    Address addr = bd.address();
                    var billing = transactionRequest.billingAddress();
                    if (bd.firstName() != null)    billing.firstName(bd.firstName());
                    if (bd.lastName() != null)     billing.lastName(bd.lastName());
                    if (addr.line1() != null)      billing.streetAddress(addr.line1());
                    if (addr.line2() != null)      billing.extendedAddress(addr.line2());
                    if (addr.city() != null)       billing.locality(addr.city());
                    if (addr.state() != null)      billing.region(addr.state());
                    if (addr.postalCode() != null) billing.postalCode(addr.postalCode());
                    if (addr.country() != null)    billing.countryCodeAlpha2(addr.country());
                    billing.done();
                }
            }

            // Shipping address — used for dispute evidence
            if (req.shippingDetails() != null && req.shippingDetails().address() != null) {
                ShippingDetails sd = req.shippingDetails();
                Address addr = sd.address();
                var shipping = transactionRequest.shippingAddress();
                if (sd.firstName() != null)    shipping.firstName(sd.firstName());
                if (sd.lastName() != null)     shipping.lastName(sd.lastName());
                if (addr.line1() != null)      shipping.streetAddress(addr.line1());
                if (addr.line2() != null)      shipping.extendedAddress(addr.line2());
                if (addr.city() != null)       shipping.locality(addr.city());
                if (addr.state() != null)      shipping.region(addr.state());
                if (addr.postalCode() != null) shipping.postalCode(addr.postalCode());
                if (addr.country() != null)    shipping.countryCodeAlpha2(addr.country());
                shipping.done();
            }

            // MANUAL capture: authorize only, do not submit for settlement yet
            boolean submitForSettlement = req.captureMethod() != CaptureMethod.MANUAL;
            transactionRequest.options()
                        .submitForSettlement(submitForSettlement)
                    .done();

            Result<Transaction> result = gateway.transaction().sale(transactionRequest);

            if (result.isSuccess()) {
                Transaction tx = result.getTarget();
                return new ChargeResult(true, tx.getId(), null, null, null, false, false, null, null, null);
            }

            // Processor declined or gateway rejection
            Transaction tx = result.getTransaction();
            if (tx != null) {
                String failureCode = switch (tx.getStatus()) {
                    case PROCESSOR_DECLINED -> "processor_declined";
                    case GATEWAY_REJECTED   -> "gateway_rejected";
                    default                 -> "payment_failed";
                };
                String message = tx.getProcessorResponseText() != null
                        ? tx.getProcessorResponseText()
                        : result.getMessage();
                boolean retryable = tx.getStatus() == Transaction.Status.GATEWAY_REJECTED;
                return new ChargeResult(false, tx.getId(), null, failureCode, message, retryable, false, null, null, null);
            }

            // Validation error (bad nonce, missing fields, etc.)
            String validationMessage = result.getMessage();
            return new ChargeResult(false, null, null, "validation_error", validationMessage, false, false, null, null, null);

        } catch (Exception e) {
            log.error("Braintree charge error", e);
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true, false, null, null, null);
        }
    }

    @Override
    public Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof BraintreeCredentials bt) || bt.privateKey() == null) return Optional.empty();
        try {
            Transaction tx = buildGateway(bt).transaction().find(providerPaymentId);
            PaymentIntentStatus mapped = switch (tx.getStatus()) {
                case SETTLED, SETTLEMENT_CONFIRMED           -> PaymentIntentStatus.SUCCEEDED;
                case VOIDED, FAILED, SETTLEMENT_DECLINED,
                     PROCESSOR_DECLINED, GATEWAY_REJECTED    -> PaymentIntentStatus.FAILED;
                default                                      -> null; // AUTHORIZED / SUBMITTED / SETTLING — in-flight
            };
            return Optional.ofNullable(mapped);
        } catch (Exception e) {
            log.warn("Braintree syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof BraintreeCredentials bt) || bt.privateKey() == null) return false;
        try {
            Result<Transaction> result = buildGateway(bt).transaction().submitForSettlement(providerPaymentId);
            if (result.isSuccess()) return true;
            log.warn("Braintree captureAtProvider failed for {}: {}", providerPaymentId, result.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Braintree captureAtProvider error for {}: {}", providerPaymentId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof BraintreeCredentials bt) || bt.privateKey() == null) return false;
        try {
            Result<Transaction> result = buildGateway(bt).transaction().voidTransaction(providerPaymentId);
            if (result.isSuccess()) return true;
            log.warn("Braintree cancelAtProvider failed for {}: {}", providerPaymentId, result.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Braintree cancelAtProvider error for {}: {}", providerPaymentId, e.getMessage());
            return false;
        }
    }

    @Override
    public RefundResult refund(RefundRequest req, ProviderCredentials creds) {
        if (!(creds instanceof BraintreeCredentials bt) || bt.privateKey() == null) {
            return new RefundResult(false, null, "No active Braintree connector found.");
        }

        BraintreeGateway gateway = buildGateway(bt);
        try {
            BigDecimal amount = new BigDecimal(req.amount()).movePointLeft(2);

            Result<Transaction> result = gateway.transaction().refund(req.providerPaymentId(), amount);

            if (result.isSuccess()) {
                return new RefundResult(true, result.getTarget().getId(), null);
            }

            return new RefundResult(false, null, result.getMessage());

        } catch (Exception e) {
            log.error("Braintree refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    private BraintreeGateway buildGateway(BraintreeCredentials bt) {
        return new BraintreeGateway(
                bt.sandbox() ? Environment.SANDBOX : Environment.PRODUCTION,
                bt.merchantId(),
                bt.publicKey(),
                bt.privateKey()
        );
    }
}
