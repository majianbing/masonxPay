package com.masonx.rail.canonical;

import com.masonx.contracts.rail.PaymentRail;

/**
 * Pluggable adapter for a specific payment rail and network.
 *
 * <p>Implementations handle all protocol-specific logic: message construction,
 * transport, response parsing, and error mapping. The rest of rail-service
 * interacts only with this interface and the canonical model.
 *
 * <p>MR1 implementations: {@code VisaSimIso8583Adapter}, {@code MastercardSimIso8583Adapter}.
 * MR3 implementations: {@code SepaSimIso20022Adapter}, {@code FedNowSimIso20022Adapter}.
 */
public interface PaymentRailAdapter {

    /** Returns true if this adapter handles the given rail and network combination. */
    boolean supports(PaymentRail rail, String network);

    /** Executes the payment command and returns a canonical response. */
    RailResponse execute(CanonicalPaymentCommand command);

    /** Queries the current status of a previously submitted payment. */
    RailResponse query(String railPaymentId);

    /**
     * Initiates a reversal (ISO 8583) or return (ISO 20022) for the given payment.
     * For card rail: sends 0400 reversal request.
     * For bank rail: initiates pacs.004 return.
     */
    RailResponse reverse(String railPaymentId, String reasonCode);
}
