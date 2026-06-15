-- Order reference and customer identity fields on payment_intents.
-- billing_details / shipping_details stored as JSON text — consistent with the
-- existing metadata column pattern. Indexed by order_id for merchant lookups.
ALTER TABLE payment_intents
    ADD COLUMN order_id        VARCHAR(255),
    ADD COLUMN description     VARCHAR(500),
    ADD COLUMN billing_details TEXT,
    ADD COLUMN shipping_details TEXT;

-- Composite index: enforces tenant isolation on order lookups
CREATE INDEX idx_payment_intents_order_id
    ON payment_intents (merchant_id, order_id)
    WHERE order_id IS NOT NULL;
