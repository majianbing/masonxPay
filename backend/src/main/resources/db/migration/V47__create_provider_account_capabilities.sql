CREATE TABLE provider_account_capabilities (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    provider_account_id UUID NOT NULL,
    payment_method_type VARCHAR(50) NOT NULL,
    country VARCHAR(2),
    currency VARCHAR(10),
    min_amount BIGINT,
    max_amount BIGINT,
    supports_manual_capture BOOLEAN NOT NULL DEFAULT TRUE,
    supports_refund BOOLEAN NOT NULL DEFAULT TRUE,
    supports_partial_refund BOOLEAN NOT NULL DEFAULT TRUE,
    supports_3ds BOOLEAN NOT NULL DEFAULT FALSE,
    supports_redirect BOOLEAN NOT NULL DEFAULT FALSE,
    supports_provider_token BOOLEAN NOT NULL DEFAULT TRUE,
    supports_vault_token BOOLEAN NOT NULL DEFAULT FALSE,
    supports_network_token BOOLEAN NOT NULL DEFAULT FALSE,
    supports_installments BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_provider_account_capabilities_amount_range
        CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount)
);

CREATE INDEX idx_provider_account_capabilities_account
    ON provider_account_capabilities (merchant_id, provider_account_id, enabled);

CREATE INDEX idx_provider_account_capabilities_method
    ON provider_account_capabilities (merchant_id, payment_method_type, country, currency, enabled);
