package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;

import java.util.Optional;

/**
 * Template Method base for all payment provider implementations.
 *
 * Layer structure:
 *   Layer 1 — PaymentProviderService (interface): defines the public contract
 *   Layer 2 — AbstractPaymentProviderService<C> (this class): implements the standard
 *             workflow — credential type validation, then delegation to the abstract
 *             send* methods. Cross-cutting concerns (future: timeout, retry budget,
 *             circuit-breaker hook) live here.
 *   Layer 3 — Concrete providers (Stripe, Square, etc.): implement only the
 *             provider-specific network calls in send* methods. No credential casting,
 *             no connector_not_configured boilerplate.
 *
 * The generic parameter C is the provider-specific credential type. Concrete classes
 * declare credentialsType() → Class<C> so the abstract class can perform a safe,
 * typed cast without unchecked warnings or duplicate instanceof checks.
 */
public abstract class AbstractPaymentProviderService<C extends ProviderCredentials>
        implements PaymentProviderService {

    // ── Abstract methods — implemented by each concrete provider ──────────────

    /** The specific credential class this provider expects. */
    protected abstract Class<C> credentialsType();

    /** Execute the charge request using typed, pre-validated credentials. */
    protected abstract ChargeResult sendCharge(ChargeRequest req, C creds);

    /** Execute the refund request using typed, pre-validated credentials. */
    protected abstract RefundResult sendRefund(RefundRequest req, C creds);

    /** Query the provider for the current payment status. */
    protected abstract Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, C creds);

    /** Submit a previously authorized payment for settlement. */
    protected abstract boolean sendCapture(String providerPaymentId, C creds);

    /** Void or cancel a payment that has not yet settled. */
    protected abstract boolean sendCancel(String providerPaymentId, C creds);

    // ── Template implementations — credential guard + delegation ──────────────

    @Override
    public final ChargeResult charge(ChargeRequest req, ProviderCredentials creds) {
        C typed = cast(creds);
        if (typed == null) return missingConnectorCharge();
        return sendCharge(req, typed);
    }

    @Override
    public final RefundResult refund(RefundRequest req, ProviderCredentials creds) {
        C typed = cast(creds);
        if (typed == null) return missingConnectorRefund();
        return sendRefund(req, typed);
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

    // ── Internal helper ───────────────────────────────────────────────────────

    private C cast(ProviderCredentials creds) {
        Class<C> type = credentialsType();
        return type.isInstance(creds) ? type.cast(creds) : null;
    }
}
