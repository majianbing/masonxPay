-- va_transaction: journal entry header — one row per PostTransaction.
--
-- Two-layer ledger design:
--   System layer (va_ledger_entry): throughput path, HMAC chain, 64-shard HASH(account_id).
--   Financial layer (va_transaction): business context, regulatory metadata, audit trail.
--
-- Partitioned: HASH(transaction_id), 64 buckets — matches va_ledger_entry shard count.
-- entry_type is VARCHAR (not a PG enum) so new types cost no migration.
-- status is POSTED-only in Phase LC; VOIDED requires reversal_of_transaction_id (future).
-- Design: docs/planning/ledger-completeness-plan.md

CREATE TABLE va_transaction (
    transaction_id       VARCHAR(32)   NOT NULL,
    entry_type           VARCHAR(50)   NOT NULL,
    description          VARCHAR(255),
    payment_reference_id VARCHAR(64),              -- gateway payment_intent_id; NULL for bench/internal
    effective_date       DATE          NOT NULL,   -- accounting date; may differ from created_at
    status               VARCHAR(20)   NOT NULL DEFAULT 'POSTED',
    mode                 va_mode       NOT NULL,
    org_id               VARCHAR(64),              -- NULL for PLATFORM/EXTERNAL transactions
    merchant_id          VARCHAR(64),              -- NULL for PLATFORM/EXTERNAL transactions
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
) PARTITION BY HASH (transaction_id);

-- 64 child partitions
CREATE TABLE va_transaction_0  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 0);
CREATE TABLE va_transaction_1  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 1);
CREATE TABLE va_transaction_2  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 2);
CREATE TABLE va_transaction_3  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 3);
CREATE TABLE va_transaction_4  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 4);
CREATE TABLE va_transaction_5  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 5);
CREATE TABLE va_transaction_6  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 6);
CREATE TABLE va_transaction_7  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 7);
CREATE TABLE va_transaction_8  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 8);
CREATE TABLE va_transaction_9  PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 9);
CREATE TABLE va_transaction_10 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 10);
CREATE TABLE va_transaction_11 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 11);
CREATE TABLE va_transaction_12 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 12);
CREATE TABLE va_transaction_13 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 13);
CREATE TABLE va_transaction_14 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 14);
CREATE TABLE va_transaction_15 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 15);
CREATE TABLE va_transaction_16 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 16);
CREATE TABLE va_transaction_17 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 17);
CREATE TABLE va_transaction_18 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 18);
CREATE TABLE va_transaction_19 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 19);
CREATE TABLE va_transaction_20 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 20);
CREATE TABLE va_transaction_21 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 21);
CREATE TABLE va_transaction_22 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 22);
CREATE TABLE va_transaction_23 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 23);
CREATE TABLE va_transaction_24 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 24);
CREATE TABLE va_transaction_25 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 25);
CREATE TABLE va_transaction_26 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 26);
CREATE TABLE va_transaction_27 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 27);
CREATE TABLE va_transaction_28 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 28);
CREATE TABLE va_transaction_29 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 29);
CREATE TABLE va_transaction_30 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 30);
CREATE TABLE va_transaction_31 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 31);
CREATE TABLE va_transaction_32 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 32);
CREATE TABLE va_transaction_33 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 33);
CREATE TABLE va_transaction_34 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 34);
CREATE TABLE va_transaction_35 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 35);
CREATE TABLE va_transaction_36 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 36);
CREATE TABLE va_transaction_37 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 37);
CREATE TABLE va_transaction_38 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 38);
CREATE TABLE va_transaction_39 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 39);
CREATE TABLE va_transaction_40 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 40);
CREATE TABLE va_transaction_41 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 41);
CREATE TABLE va_transaction_42 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 42);
CREATE TABLE va_transaction_43 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 43);
CREATE TABLE va_transaction_44 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 44);
CREATE TABLE va_transaction_45 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 45);
CREATE TABLE va_transaction_46 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 46);
CREATE TABLE va_transaction_47 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 47);
CREATE TABLE va_transaction_48 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 48);
CREATE TABLE va_transaction_49 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 49);
CREATE TABLE va_transaction_50 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 50);
CREATE TABLE va_transaction_51 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 51);
CREATE TABLE va_transaction_52 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 52);
CREATE TABLE va_transaction_53 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 53);
CREATE TABLE va_transaction_54 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 54);
CREATE TABLE va_transaction_55 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 55);
CREATE TABLE va_transaction_56 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 56);
CREATE TABLE va_transaction_57 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 57);
CREATE TABLE va_transaction_58 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 58);
CREATE TABLE va_transaction_59 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 59);
CREATE TABLE va_transaction_60 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 60);
CREATE TABLE va_transaction_61 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 61);
CREATE TABLE va_transaction_62 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 62);
CREATE TABLE va_transaction_63 PARTITION OF va_transaction FOR VALUES WITH (MODULUS 64, REMAINDER 63);

-- PK and query indexes on each va_transaction child partition.
DO $$
DECLARE i INT;
BEGIN
    FOR i IN 0..63 LOOP
        EXECUTE format(
            'ALTER TABLE va_transaction_%s ADD PRIMARY KEY (transaction_id)',
            i
        );
        -- Merchant period audit: list transactions for a merchant in a date range
        EXECUTE format(
            'CREATE INDEX ON va_transaction_%s (merchant_id, effective_date) WHERE merchant_id IS NOT NULL',
            i
        );
        -- Reverse-lookup from gateway payment reference
        EXECUTE format(
            'CREATE INDEX ON va_transaction_%s (payment_reference_id) WHERE payment_reference_id IS NOT NULL',
            i
        );
        -- Admin audit: filter by type + mode + date
        EXECUTE format(
            'CREATE INDEX ON va_transaction_%s (entry_type, mode, effective_date)',
            i
        );
    END LOOP;
END $$;

-- ── effective_date on va_ledger_entry (3-step — avoids volatile CURRENT_DATE default) ──

-- Step 1: add nullable (metadata-only change; no table rewrite on partitioned table)
ALTER TABLE va_ledger_entry ADD COLUMN effective_date DATE;

-- Step 2: backfill from system timestamp — historically accurate for all environments
UPDATE va_ledger_entry SET effective_date = created_at::date WHERE effective_date IS NULL;

-- Step 3: enforce NOT NULL now that every row has a value
ALTER TABLE va_ledger_entry ALTER COLUMN effective_date SET NOT NULL;

-- ── New per-partition indexes on va_ledger_entry ──
-- Added in same migration to keep index state consistent with schema version.
DO $$
DECLARE i INT;
BEGIN
    FOR i IN 0..63 LOOP
        -- Audit path: find all entries for a transaction (fans out across all 64 partitions;
        -- acceptable for audit only — documented in docs/planning/ledger-completeness-plan.md)
        EXECUTE format(
            'CREATE INDEX ON va_ledger_entry_%s (transaction_id)',
            i
        );
        -- Statement queries: account-scoped date-range sums and entry list.
        -- account_id is the shard key so this always hits exactly one partition.
        EXECUTE format(
            'CREATE INDEX ON va_ledger_entry_%s (account_id, effective_date, entry_seq)',
            i
        );
    END LOOP;
END $$;
