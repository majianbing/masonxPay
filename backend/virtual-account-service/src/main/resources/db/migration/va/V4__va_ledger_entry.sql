-- va_ledger_entry: append-only, immutable double-entry ledger.
--
-- Partitioned: HASH(ledger_account_id), 64 buckets.
-- All performance-critical queries (balance, history, reconciliation) are
-- scoped by ledger_account_id — entries stay co-located in one bucket per account.
--
-- NEVER UPDATE or DELETE rows. Corrections are new compensating entries.
-- Design: docs/engineering/virtual-account-guide.md

CREATE TYPE va_entry_direction AS ENUM ('DEBIT', 'CREDIT');
CREATE TYPE va_entry_status    AS ENUM ('POSTED', 'REVERSED');

CREATE TABLE va_ledger_entry (
    entry_id          VARCHAR(32)         NOT NULL,  -- snowflake: le_{id}
    transaction_id    VARCHAR(32)         NOT NULL,  -- snowflake: tx_{id}; groups balanced entry set
    ledger_account_id        VARCHAR(32)         NOT NULL,
    direction         va_entry_direction  NOT NULL,
    amount            NUMERIC(38, 8)      NOT NULL CHECK (amount > 0),
    asset             VARCHAR(20)         NOT NULL,

    -- Per-account monotonic counter. Defines HMAC chain order.
    -- NOT created_at — timestamps are non-deterministic under concurrency.
    entry_seq         BIGINT              NOT NULL,

    -- Running balance snapshot after this entry is applied.
    balance_after     NUMERIC(38, 8)      NOT NULL,

    -- HMAC-SHA256 tamper-evident chain.
    -- Inputs: ledger_account_id || entry_seq || amount || asset || direction
    --         || balance_after || transaction_id || prev_signature || signature_key_id
    balance_signature VARCHAR(64)         NOT NULL,

    -- Traceability back to the upstream event.
    -- Dedup is enforced by va_inbox_event (first line) and the ledger-entry
    -- unique key. V17 adds source_event_leg so one event can touch the same
    -- account through multiple semantic legs without losing DB idempotency.
    source_event_id   VARCHAR(64)         NOT NULL,

    status            va_entry_status     NOT NULL DEFAULT 'POSTED',
    created_at        TIMESTAMPTZ         NOT NULL DEFAULT now()

) PARTITION BY HASH (ledger_account_id);

-- 64 child partitions
CREATE TABLE va_ledger_entry_0  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 0);
CREATE TABLE va_ledger_entry_1  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 1);
CREATE TABLE va_ledger_entry_2  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 2);
CREATE TABLE va_ledger_entry_3  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 3);
CREATE TABLE va_ledger_entry_4  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 4);
CREATE TABLE va_ledger_entry_5  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 5);
CREATE TABLE va_ledger_entry_6  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 6);
CREATE TABLE va_ledger_entry_7  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 7);
CREATE TABLE va_ledger_entry_8  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 8);
CREATE TABLE va_ledger_entry_9  PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 9);
CREATE TABLE va_ledger_entry_10 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 10);
CREATE TABLE va_ledger_entry_11 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 11);
CREATE TABLE va_ledger_entry_12 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 12);
CREATE TABLE va_ledger_entry_13 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 13);
CREATE TABLE va_ledger_entry_14 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 14);
CREATE TABLE va_ledger_entry_15 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 15);
CREATE TABLE va_ledger_entry_16 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 16);
CREATE TABLE va_ledger_entry_17 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 17);
CREATE TABLE va_ledger_entry_18 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 18);
CREATE TABLE va_ledger_entry_19 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 19);
CREATE TABLE va_ledger_entry_20 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 20);
CREATE TABLE va_ledger_entry_21 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 21);
CREATE TABLE va_ledger_entry_22 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 22);
CREATE TABLE va_ledger_entry_23 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 23);
CREATE TABLE va_ledger_entry_24 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 24);
CREATE TABLE va_ledger_entry_25 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 25);
CREATE TABLE va_ledger_entry_26 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 26);
CREATE TABLE va_ledger_entry_27 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 27);
CREATE TABLE va_ledger_entry_28 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 28);
CREATE TABLE va_ledger_entry_29 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 29);
CREATE TABLE va_ledger_entry_30 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 30);
CREATE TABLE va_ledger_entry_31 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 31);
CREATE TABLE va_ledger_entry_32 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 32);
CREATE TABLE va_ledger_entry_33 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 33);
CREATE TABLE va_ledger_entry_34 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 34);
CREATE TABLE va_ledger_entry_35 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 35);
CREATE TABLE va_ledger_entry_36 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 36);
CREATE TABLE va_ledger_entry_37 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 37);
CREATE TABLE va_ledger_entry_38 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 38);
CREATE TABLE va_ledger_entry_39 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 39);
CREATE TABLE va_ledger_entry_40 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 40);
CREATE TABLE va_ledger_entry_41 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 41);
CREATE TABLE va_ledger_entry_42 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 42);
CREATE TABLE va_ledger_entry_43 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 43);
CREATE TABLE va_ledger_entry_44 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 44);
CREATE TABLE va_ledger_entry_45 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 45);
CREATE TABLE va_ledger_entry_46 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 46);
CREATE TABLE va_ledger_entry_47 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 47);
CREATE TABLE va_ledger_entry_48 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 48);
CREATE TABLE va_ledger_entry_49 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 49);
CREATE TABLE va_ledger_entry_50 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 50);
CREATE TABLE va_ledger_entry_51 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 51);
CREATE TABLE va_ledger_entry_52 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 52);
CREATE TABLE va_ledger_entry_53 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 53);
CREATE TABLE va_ledger_entry_54 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 54);
CREATE TABLE va_ledger_entry_55 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 55);
CREATE TABLE va_ledger_entry_56 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 56);
CREATE TABLE va_ledger_entry_57 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 57);
CREATE TABLE va_ledger_entry_58 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 58);
CREATE TABLE va_ledger_entry_59 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 59);
CREATE TABLE va_ledger_entry_60 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 60);
CREATE TABLE va_ledger_entry_61 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 61);
CREATE TABLE va_ledger_entry_62 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 62);
CREATE TABLE va_ledger_entry_63 PARTITION OF va_ledger_entry FOR VALUES WITH (MODULUS 64, REMAINDER 63);

-- PK and dedup constraint on each child partition.
-- V17 replaces the original UNIQUE(ledger_account_id, source_event_id) with
-- UNIQUE(ledger_account_id, source_event_id, source_event_leg).
-- Prevents the same upstream event from posting twice to the same account.
DO $$
DECLARE i INT;
BEGIN
    FOR i IN 0..63 LOOP
        EXECUTE format(
            'ALTER TABLE va_ledger_entry_%s ADD PRIMARY KEY (ledger_account_id, entry_id)',
            i
        );
        EXECUTE format(
            'CREATE UNIQUE INDEX ON va_ledger_entry_%s (ledger_account_id, source_event_id)',
            i
        );
        -- Chain lookup: fetch last entry for an account ordered by entry_seq
        EXECUTE format(
            'CREATE INDEX ON va_ledger_entry_%s (ledger_account_id, entry_seq DESC)',
            i
        );
    END LOOP;
END $$;
