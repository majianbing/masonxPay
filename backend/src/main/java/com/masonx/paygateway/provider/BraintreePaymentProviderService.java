package com.masonx.paygateway.provider;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Environment;
import com.braintreegateway.Result;
import com.braintreegateway.Transaction;
import com.braintreegateway.TransactionRequest;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.BraintreeCredentials;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
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
                    false);
        }

        BraintreeGateway gateway = buildGateway(bt);
        try {
            // Amount: convert cents → dollars (Braintree requires decimal dollars)
            BigDecimal amount = new BigDecimal(req.amount()).movePointLeft(2);

            TransactionRequest transactionRequest = new TransactionRequest()
                    .amount(amount)
                    .paymentMethodNonce(req.paymentMethodId())  // nonce from Drop-in UI
                    .orderId(req.idempotencyKey())              // stored for our audit trail
                    .options()
                        .submitForSettlement(true)
                    .done();

            Result<Transaction> result = gateway.transaction().sale(transactionRequest);

            if (result.isSuccess()) {
                Transaction tx = result.getTarget();
                return new ChargeResult(true, tx.getId(), null, null, null, false);
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
                // Hard card declines are non-retryable
                boolean retryable = tx.getStatus() == Transaction.Status.GATEWAY_REJECTED;
                return new ChargeResult(false, tx.getId(), null, failureCode, message, retryable);
            }

            // Validation error (bad nonce, missing fields, etc.)
            String validationMessage = result.getMessage();
            return new ChargeResult(false, null, null, "validation_error", validationMessage, false);

        } catch (Exception e) {
            log.error("Braintree charge error", e);
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true);
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
