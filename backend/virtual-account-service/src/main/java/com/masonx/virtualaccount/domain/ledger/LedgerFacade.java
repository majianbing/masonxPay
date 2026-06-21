package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.inbound.InboxRepository;
import org.springframework.stereotype.Service;

/**
 * Application-service facade for double-entry posting.
 *
 * Two posting paths with explicit semantics:
 *
 *   postIfNew(tx, eventId, eventType)
 *     For event-driven callers (Kafka consumers, webhooks). Idempotency is
 *     enforced HERE — the inbox check runs unconditionally before the engine.
 *     Returns false and does nothing on duplicate delivery. Callers do not
 *     need to (and must not) call InboxRepository separately.
 *
 *   postDirect(tx)
 *     For callers that have no event identity (bench, internal corrections,
 *     future admin API). Bypasses inbox intentionally. The UNIQUE(account_id,
 *     source_event_id) DB constraint is the only backstop here — duplicate
 *     source_event_ids on the same account throw DataIntegrityViolationException.
 *
 * Cross-cutting concerns that apply to all postings (observability, audit
 * fan-out, future rate-limiting) belong in this class, not in the engine.
 */
@Service
public class LedgerFacade {

    private final LedgerPostingService engine;
    private final InboxRepository      inbox;

    public LedgerFacade(LedgerPostingService engine, InboxRepository inbox) {
        this.engine = engine;
        this.inbox  = inbox;
    }

    /**
     * Posts a transaction only if {@code eventId} has not been seen before.
     *
     * @return true if the transaction was posted; false if it was a duplicate
     *         and was silently skipped.
     */
    public boolean postIfNew(PostTransaction tx, String eventId, String eventType) {
        if (!inbox.markProcessed(eventId, eventType)) {
            return false;
        }
        engine.post(tx);
        return true;
    }

    /**
     * Posts a transaction unconditionally, bypassing inbox deduplication.
     * Use only for callers that have no event identity.
     */
    public void postDirect(PostTransaction tx) {
        engine.post(tx);
    }
}
