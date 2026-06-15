CREATE TABLE customers (
    id            UUID PRIMARY KEY,
    merchant_id   UUID NOT NULL,
    email         VARCHAR(320),
    name          VARCHAR(200),
    metadata_json TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customers_merchant_created
    ON customers (merchant_id, created_at DESC);

CREATE INDEX idx_customers_merchant_email
    ON customers (merchant_id, email);

CREATE TABLE customer_payment_methods (
    id                    UUID PRIMARY KEY,
    merchant_id           UUID NOT NULL,
    customer_id           UUID NOT NULL,
    payment_instrument_id UUID NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_default            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_payment_methods_instrument
        UNIQUE (merchant_id, customer_id, payment_instrument_id)
);

CREATE INDEX idx_customer_payment_methods_customer
    ON customer_payment_methods (merchant_id, customer_id, created_at DESC);

CREATE UNIQUE INDEX uq_customer_payment_methods_default
    ON customer_payment_methods (merchant_id, customer_id)
    WHERE is_default = TRUE AND status = 'ACTIVE';
