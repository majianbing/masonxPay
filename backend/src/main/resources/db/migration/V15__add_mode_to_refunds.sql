-- Refunds inherit the mode of their parent payment intent.
-- Denormalised here (same pattern as gateway_logs) to allow simple indexed filtering
-- without joins on every dashboard list query.
ALTER TABLE refunds ADD COLUMN mode VARCHAR(10);

-- Back-fill from parent payment_intents
UPDATE refunds r
SET mode = (SELECT pi.mode FROM payment_intents pi WHERE pi.id = r.payment_intent_id);

-- Make NOT NULL now that all rows are filled
ALTER TABLE refunds ALTER COLUMN mode SET NOT NULL;

CREATE INDEX idx_refunds_merchant_mode ON refunds(merchant_id, mode);
