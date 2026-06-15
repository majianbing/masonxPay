-- Virtual Account service baseline.
--
-- Inbox table for idempotent (exactly-once-effect) event consumption: the
-- consumer inserts each event id once; a redelivered event is a no-op.
-- Domain ledger/account tables (va_account, va_ledger_entry, ...) are owned by
-- the VA domain design (see docs/engineering/virtual-account-guide.md) and are
-- added in later migrations.
CREATE TABLE va_inbox_event (
    event_id    UUID         PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
