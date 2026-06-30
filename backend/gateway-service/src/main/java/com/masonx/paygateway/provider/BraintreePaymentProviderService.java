package com.masonx.paygateway.provider;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.CustomerRequest;
import com.braintreegateway.Environment;
import com.braintreegateway.PaymentMethod;
import com.braintreegateway.PaymentMethodRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Braintree payment provider — uses the official Braintree Java SDK.
 *
 * Amount: stored in the gateway as minor currency units (cents). Braintree expects a
 * BigDecimal in major units (dollars), so we shift the decimal two places left.
 *
 * Idempotency: Braintree has no native idempotency key header. We store our internal
 * key in the transaction's orderId field. Duplicate protection is still enforced by our
 * own DB unique constraint on (merchant_id, idempotency_key).
 */
@Service
public class BraintreePaymentProviderService
        extends AbstractPaymentProviderService<BraintreeCredentials>
        implements ReusablePaymentMethodProviderService {

    private static final Logger log = LoggerFactory.getLogger(BraintreePaymentProviderService.class);

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.BRAINTREE;
    }

    @Override
    protected Class<BraintreeCredentials> credentialsType() {
        return BraintreeCredentials.class;
    }

    @Override
    protected ChargeResult sendCharge(ChargeRequest req, BraintreeCredentials bt) {
        if (bt.privateKey() == null) return missingConnectorCharge();

        BraintreeGateway gateway = buildGateway(bt);
        try {
            BigDecimal amount = new BigDecimal(req.amount()).movePointLeft(2);

            TransactionRequest transactionRequest = new TransactionRequest()
                    .amount(amount)
                    .orderId(req.idempotencyKey());
            if (req.providerCustomerReference() != null && !req.providerCustomerReference().isBlank()) {
                transactionRequest.paymentMethodToken(req.paymentMethodId());
            } else {
                transactionRequest.paymentMethodNonce(req.paymentMethodId());
            }

            if (req.billingDetails() != null) {
                BillingDetails bd = req.billingDetails();
                CustomerRequest customer = transactionRequest.customer();
                if (bd.firstName() != null) customer.firstName(bd.firstName());
                if (bd.lastName() != null)  customer.lastName(bd.lastName());
                if (bd.email() != null)     customer.email(bd.email());
                if (bd.phone() != null)     customer.phone(bd.phone());
                customer.done();

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

            transactionRequest.options()
                    .submitForSettlement(req.captureMethod() != CaptureMethod.MANUAL)
                    .done();

            Result<Transaction> result = gateway.transaction().sale(transactionRequest);

            if (result.isSuccess()) {
                Transaction tx = result.getTarget();
                return new ChargeResult(true, tx.getId(), null, null, null, false, false, false, null, null, null);
            }

            Transaction tx = result.getTransaction();
            if (tx != null) {
                String failureCode = switch (tx.getStatus()) {
                    case PROCESSOR_DECLINED -> "processor_declined";
                    case GATEWAY_REJECTED   -> "gateway_rejected";
                    default                 -> "payment_failed";
                };
                boolean retryable = tx.getStatus() == Transaction.Status.GATEWAY_REJECTED;
                return new ChargeResult(false, tx.getId(), null, failureCode,
                        tx.getProcessorResponseText() != null ? tx.getProcessorResponseText() : result.getMessage(),
                        retryable, false, false, null, null, null);
            }

            return new ChargeResult(false, null, null, "validation_error", result.getMessage(),
                    false, false, false, null, null, null);

        } catch (Exception e) {
            log.error("Braintree charge error", e);
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true, false, false, null, null, null);
        }
    }

    @Override
    protected RefundResult sendRefund(RefundRequest req, BraintreeCredentials bt) {
        if (bt.privateKey() == null) return missingConnectorRefund();
        try {
            BigDecimal amount = new BigDecimal(req.amount()).movePointLeft(2);
            Result<Transaction> result = buildGateway(bt).transaction().refund(req.providerPaymentId(), amount);
            if (result.isSuccess()) return new RefundResult(true, result.getTarget().getId(), null);
            return new RefundResult(false, null, result.getMessage());
        } catch (Exception e) {
            log.error("Braintree refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    @Override
    protected Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, BraintreeCredentials bt) {
        if (bt.privateKey() == null) return Optional.empty();
        try {
            Transaction tx = buildGateway(bt).transaction().find(providerPaymentId);
            PaymentIntentStatus mapped = switch (tx.getStatus()) {
                case SETTLED, SETTLEMENT_CONFIRMED          -> PaymentIntentStatus.SUCCEEDED;
                case VOIDED, FAILED, SETTLEMENT_DECLINED,
                     PROCESSOR_DECLINED, GATEWAY_REJECTED   -> PaymentIntentStatus.FAILED;
                default                                     -> null;
            };
            return Optional.ofNullable(mapped);
        } catch (Exception e) {
            log.warn("Braintree syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    protected boolean sendCapture(String providerPaymentId, BraintreeCredentials bt) {
        if (bt.privateKey() == null) return false;
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
    protected boolean sendCancel(String providerPaymentId, BraintreeCredentials bt) {
        if (bt.privateKey() == null) return false;
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

    // ── ReusablePaymentMethodProviderService ──────────────────────────────────

    @Override
    public ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request, ProviderCredentials creds) {
        if (!(creds instanceof BraintreeCredentials bt) || bt.privateKey() == null) {
            return ReusablePaymentMethodSetupResult.failed("connector_not_configured",
                    "No active Braintree connector found. Add one under Settings → Connectors.", false);
        }
        if (request.providerPaymentMethodId() == null || request.providerPaymentMethodId().isBlank()) {
            return ReusablePaymentMethodSetupResult.failed("missing_payment_method",
                    "Braintree reusable setup requires a Drop-in payment method nonce.", false);
        }

        try {
            BraintreeGateway gateway = buildGateway(bt);
            if (request.existingProviderCustomerReference() != null
                    && !request.existingProviderCustomerReference().isBlank()) {
                PaymentMethodRequest methodRequest = new PaymentMethodRequest()
                        .customerId(request.existingProviderCustomerReference())
                        .paymentMethodNonce(request.providerPaymentMethodId());
                Result<? extends PaymentMethod> methodResult = gateway.paymentMethod().create(methodRequest);
                if (!methodResult.isSuccess()) {
                    return ReusablePaymentMethodSetupResult.failed("validation_error", methodResult.getMessage(), false);
                }
                PaymentMethod pm = methodResult.getTarget();
                return ReusablePaymentMethodSetupResult.succeeded(pm.getCustomerId(), pm.getToken(),
                        "{\"provider\":\"BRAINTREE\",\"customerId\":\"" + pm.getCustomerId()
                                + "\",\"paymentMethodToken\":\"" + pm.getToken() + "\"}");
            }

            CustomerRequest customerRequest = new CustomerRequest()
                    .paymentMethodNonce(request.providerPaymentMethodId());
            if (request.billingDetails() != null) {
                if (request.billingDetails().firstName() != null)
                    customerRequest.firstName(request.billingDetails().firstName());
                if (request.billingDetails().lastName() != null)
                    customerRequest.lastName(request.billingDetails().lastName());
                if (request.billingDetails().email() != null)
                    customerRequest.email(request.billingDetails().email());
                if (request.billingDetails().phone() != null)
                    customerRequest.phone(request.billingDetails().phone());
            }

            Result<com.braintreegateway.Customer> result = gateway.customer().create(customerRequest);
            if (!result.isSuccess()) {
                return ReusablePaymentMethodSetupResult.failed("validation_error", result.getMessage(), false);
            }
            com.braintreegateway.Customer customer = result.getTarget();
            PaymentMethod defaultMethod = customer.getDefaultPaymentMethod();
            String reusableToken = defaultMethod != null ? defaultMethod.getToken() : null;
            if (reusableToken == null && !customer.getPaymentMethods().isEmpty()) {
                reusableToken = customer.getPaymentMethods().get(0).getToken();
            }
            if (reusableToken == null || reusableToken.isBlank()) {
                return ReusablePaymentMethodSetupResult.failed("vault_token_missing",
                        "Braintree did not return a vaulted payment method token.", true);
            }
            return ReusablePaymentMethodSetupResult.succeeded(customer.getId(), reusableToken,
                    "{\"provider\":\"BRAINTREE\",\"customerId\":\"" + customer.getId()
                            + "\",\"paymentMethodToken\":\"" + reusableToken + "\"}");
        } catch (Exception e) {
            log.error("Braintree reusable payment method setup error", e);
            return ReusablePaymentMethodSetupResult.failed("gateway_error", e.getMessage(), true);
        }
    }

    // ── Public helpers used by controllers ────────────────────────────────────

    public String generateClientToken(BraintreeCredentials bt) {
        return buildGateway(bt).clientToken().generate();
    }

    BraintreeGateway buildGateway(BraintreeCredentials bt) {
        return new BraintreeGateway(
                bt.sandbox() ? Environment.SANDBOX : Environment.PRODUCTION,
                bt.merchantId(),
                bt.publicKey(),
                bt.privateKey()
        );
    }
}
