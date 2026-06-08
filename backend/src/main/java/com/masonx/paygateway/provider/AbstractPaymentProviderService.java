package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;

import java.util.Optional;

/**
 * Template Method base for all payment provider implementations.
 *
 * Three-layer architecture:
 *
 *   L1  PaymentProviderService (interface)
 *         Public contract. Callers depend only on this.
 *
 *   L2  AbstractPaymentProviderService<C> (this class)
 *         Standard workflow: credential resolution, pre/post cut-points,
 *         delegation to the abstract send* methods.
 *         Cross-cutting concerns live here — one change propagates to every
 *         provider automatically.
 *
 *   L3  Concrete providers (Stripe, Square, Braintree, Mollie, Simulator)
 *         Only the provider-specific network calls. No credential casting,
 *         no workflow boilerplate.
 *
 * Cut-points in charge() (canonical example — other operations follow
 * the same structure once their hooks are needed):
 *
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │  charge()                                                           │
 *   │                                                                     │
 *   │  [1] validateRequest    ← input guard, no network call yet         │
 *   │  [2] credential cast    ← type-safe; returns failure on mismatch   │
 *   │  [3] beforeCharge       ← pre-send gate (circuit breaker, etc.)    │
 *   │  [4] sendCharge         ← abstract: provider-specific network call │
 *   │  [5] afterCharge        ← post-send (metrics, fan-out, CB update)  │
 *   └─────────────────────────────────────────────────────────────────────┘
 */
public abstract class AbstractPaymentProviderService<C extends ProviderCredentials>
        implements PaymentProviderService {

    // ── Abstract methods — each concrete provider must implement ──────────────

    /** The credential class this provider expects. Enables type-safe casting in L2. */
    protected abstract Class<C> credentialsType();

    /** Execute the charge request against the provider's API. */
    protected abstract ChargeResult sendCharge(ChargeRequest req, C creds);

    /** Execute the refund request against the provider's API. */
    protected abstract RefundResult sendRefund(RefundRequest req, C creds);

    /** Query the provider for the current payment status. */
    protected abstract Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, C creds);

    /** Submit a previously authorized payment for settlement. */
    protected abstract boolean sendCapture(String providerPaymentId, C creds);

    /** Void or cancel a payment that has not yet settled. */
    protected abstract boolean sendCancel(String providerPaymentId, C creds);

    // ── Cut-point hooks — override in a subclass or future sub-abstract layer ─

    /**
     * CUT-POINT [1] — Pre-flight validation.
     *
     * Called before any credential check or network call. Default: no-op.
     *
     * Override to enforce global or provider-specific input rules without
     * touching the provider SDK. Examples:
     *   - Reject zero or negative amounts before they reach the provider.
     *   - Enforce a currency whitelist for a specific provider.
     *   - Validate idempotency key format or length.
     *
     * Throw IllegalArgumentException to reject the request; the exception
     * propagates to the caller and no provider call is made.
     */
    protected void validateRequest(ChargeRequest req) {}

    /**
     * CUT-POINT [3] — Pre-send gate.
     *
     * Called after credentials are resolved but before the network call.
     * Default: no-op.
     *
     * Examples:
     *   - Circuit-breaker open check: throw if this provider's circuit is
     *     open so the routing orchestrator can try a fallback route without
     *     wasting a real network round-trip.
     *   - Per-provider rate-limit enforcement (e.g. Bucket4j token bucket)
     *     to stay within PSP API quotas.
     *   - Capability pre-check: verify the provider account actually supports
     *     the requested currency/payment-method before making the call.
     *   - Pre-send audit event: fan-out a charge-initiated record to Kafka
     *     for real-time observability before the outcome is known.
     */
    protected void beforeCharge(ChargeRequest req, C creds) {}

    /**
     * CUT-POINT [5] — Post-send processing.
     *
     * Called after every sendCharge() regardless of outcome — including
     * requiresAction (3DS) and soft failure results. Default: no-op.
     *
     * durationMs is the wall-clock time of the sendCharge() call, measured
     * here so each provider does not need to track it individually.
     *
     * Examples:
     *   - Record success/failure to the circuit-breaker state machine so
     *     consecutive failures open the circuit automatically.
     *   - Publish provider-call metrics: latency histogram, success/failure
     *     counters with provider and failure-code tags (feeds ConnectorHealthService).
     *   - Fan-out a provider-call-completed event to Kafka for audit trails
     *     or downstream analytics without blocking the payment thread.
     *   - Update a per-provider rolling success-rate cache in Redis so the
     *     routing engine sees near-real-time health without a DB query.
     */
    protected void afterCharge(ChargeRequest req, ChargeResult result, long durationMs) {}

    // ── Template implementations ──────────────────────────────────────────────

    /**
     * Charge workflow — canonical cut-point sequence.
     * Other operations (refund, syncStatus, capture, cancel) follow the same
     * structure; their beforeX / afterX hooks can be added here as needed.
     */
    @Override
    public final ChargeResult charge(ChargeRequest req, ProviderCredentials creds) {
        // [1] Pre-flight validation — reject bad input before any network call
        validateRequest(req);

        // [2] Credential resolution — type-safe cast; returns connector_not_configured on mismatch
        C typed = cast(creds);
        if (typed == null) return missingConnectorCharge();

        // [3] Pre-send gate — circuit breaker, rate limit, capability check, pre-send fan-out
        beforeCharge(req, typed);

        // [4] Provider network call — implemented by each concrete provider in L3
        long start = System.currentTimeMillis();
        ChargeResult result = sendCharge(req, typed);
        long durationMs = System.currentTimeMillis() - start;

        // [5] Post-send processing — metrics, circuit-breaker feedback, post-send fan-out
        afterCharge(req, result, durationMs);

        return result;
    }

    @Override
    public final RefundResult refund(RefundRequest req, ProviderCredentials creds) {
        C typed = cast(creds);
        if (typed == null) return missingConnectorRefund();
        return sendRefund(req, typed);
        // Future: add beforeRefund / afterRefund hooks following the charge() pattern
    }

    @Override
    public final Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds) {
        C typed = cast(creds);
        if (typed == null) return Optional.empty();
        return sendSyncStatus(providerPaymentId, typed);
    }

    @Override
    public final boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
        C typed = cast(creds);
        if (typed == null) return false;
        return sendCapture(providerPaymentId, typed);
    }

    @Override
    public final boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
        C typed = cast(creds);
        if (typed == null) return false;
        return sendCancel(providerPaymentId, typed);
    }

    // ── Shared failure result factories ───────────────────────────────────────

    protected ChargeResult missingConnectorCharge() {
        return new ChargeResult(false, null, null,
                "connector_not_configured",
                "No active " + brand() + " connector found. Add one under Settings → Connectors.",
                false, false, null, null, null);
    }

    protected RefundResult missingConnectorRefund() {
        return new RefundResult(false, null,
                "No active " + brand() + " connector found.");
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private C cast(ProviderCredentials creds) {
        Class<C> type = credentialsType();
        return type.isInstance(creds) ? type.cast(creds) : null;
    }
}
