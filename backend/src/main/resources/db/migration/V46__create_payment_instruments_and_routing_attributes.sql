CREATE TABLE payment_instruments (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    customer_id UUID,
    type VARCHAR(30) NOT NULL,
    source VARCHAR(30) NOT NULL,
    portability VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    provider VARCHAR(30),
    provider_account_id UUID,
    token_reference TEXT NOT NULL,
    card_brand VARCHAR(30),
    last4 VARCHAR(4),
    expiry_month INT,
    expiry_year INT,
    bin_country VARCHAR(2),
    issuer_country VARCHAR(2),
    card_type VARCHAR(30),
    wallet_type VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_instruments_merchant_customer
    ON payment_instruments (merchant_id, customer_id);

CREATE INDEX idx_payment_instruments_merchant_provider_account
    ON payment_instruments (merchant_id, provider_account_id);

CREATE TABLE routing_attributes (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    attribute_key VARCHAR(100) NOT NULL,
    label VARCHAR(120) NOT NULL,
    type VARCHAR(30) NOT NULL,
    source VARCHAR(30) NOT NULL,
    allowed_operators TEXT NOT NULL,
    enum_values TEXT,
    required_before_routing BOOLEAN NOT NULL DEFAULT FALSE,
    pii_classification VARCHAR(30) NOT NULL DEFAULT 'NONE',
    max_value_length INT NOT NULL DEFAULT 255,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_routing_attributes_merchant_key UNIQUE (merchant_id, attribute_key),
    CONSTRAINT chk_routing_attributes_max_value_length
        CHECK (max_value_length > 0 AND max_value_length <= 1000)
);

CREATE INDEX idx_routing_attributes_merchant_enabled
    ON routing_attributes (merchant_id, enabled, attribute_key);
