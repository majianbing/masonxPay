-- Payment intents are now routed to payment_intents_00..63. A single refunds
-- table cannot keep a database FK to the old logical payment_intents parent
-- table because successful intents no longer live there.
--
-- RefundService still validates merchant ownership and intent existence before
-- inserting refunds; this migration only removes the impossible cross-shard FK.
ALTER TABLE refunds
    DROP CONSTRAINT IF EXISTS refunds_payment_intent_id_fkey;
