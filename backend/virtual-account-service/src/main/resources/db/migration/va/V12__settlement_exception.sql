-- settlement_exception: durable parking for settlement events that could not post.
--
-- Invariant: a settlement event delivery ends in exactly one of three states —
-- posted, deduped as replay, or PARKED here with payload + reason. Log-and-drop
-- is never a terminal state for money movement.
--
-- The stored payload is the exact object the retry path re-drives through the
-- original handler (RailSettlementEvent for RAIL_SETTLEMENT, VA-native
-- RecordSettlementCommand for GATEWAY_SETTLEMENT). Replay is safe because the
-- inbox and UNIQUE(ledger_account_id, source_event_id) make posting idempotent.
--
-- UNIQUE(event_id): a redelivered failing event updates the existing row
-- (delivery_count++) instead of duplicating; a redelivery after DISCARDED
-- reopens the row so a still-arriving event is never invisible.

CREATE TYPE settlement_exception_status AS ENUM ('OPEN', 'RESOLVED', 'DISCARDED');

CREATE TABLE settlement_exception (
    exception_id    VARCHAR(40)  PRIMARY KEY,          -- snowflake: sexc_{id}
    source          VARCHAR(30)  NOT NULL,             -- RAIL_SETTLEMENT / GATEWAY_SETTLEMENT / UNKNOWN
    event_id        VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL,             -- internal contract event; masked PAN only, never raw provider data
    reason_code     VARCHAR(40)  NOT NULL,
    error_detail    TEXT,
    status          settlement_exception_status NOT NULL DEFAULT 'OPEN',
    delivery_count  INT          NOT NULL DEFAULT 1,   -- how many times this event failed to post
    retry_count     INT          NOT NULL DEFAULT 0,   -- how many ops-initiated retries ran
    resolution_note TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_settlement_exception_event ON settlement_exception (event_id);

-- Ops worklist: open exceptions, newest first.
CREATE INDEX idx_settlement_exception_status ON settlement_exception (status, created_at DESC);
