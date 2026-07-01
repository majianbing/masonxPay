ALTER TABLE merchants
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE merchants
SET external_id = 'mer_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM merchants
    WHERE external_id IS NULL
) numbered
WHERE merchants.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_merchants_external_id
    ON merchants (external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE customers
SET external_id = 'cus_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM customers
    WHERE external_id IS NULL
) numbered
WHERE customers.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_customers_external_id
    ON customers (external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE subscriptions
SET external_id = 'sub_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM subscriptions
    WHERE external_id IS NULL
) numbered
WHERE subscriptions.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_subscriptions_external_id
    ON subscriptions (external_id)
    WHERE external_id IS NOT NULL;

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(40);

UPDATE invoices
SET external_id = 'inv_' || numbered.rn
FROM (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn
    FROM invoices
    WHERE external_id IS NULL
) numbered
WHERE invoices.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_invoices_external_id
    ON invoices (external_id)
    WHERE external_id IS NOT NULL;
