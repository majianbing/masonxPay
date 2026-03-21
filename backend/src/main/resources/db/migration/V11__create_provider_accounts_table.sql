CREATE TABLE provider_accounts (
    id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id              UUID          NOT NULL REFERENCES merchants(id),
    provider                 VARCHAR(30)   NOT NULL,               -- STRIPE | PAYPAL
    label                    VARCHAR(100)  NOT NULL,               -- user-friendly name
    encrypted_secret_key     TEXT          NOT NULL,               -- AES-256-GCM, never returned in API
    encrypted_publishable_key TEXT,                                -- nullable, for future browser SDK
    secret_key_hint          VARCHAR(20)   NOT NULL,               -- last 4 chars, safe to expose
    is_primary               BOOLEAN       NOT NULL DEFAULT false,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_provider_accounts_merchant_id ON provider_accounts(merchant_id);
CREATE INDEX idx_provider_accounts_merchant_provider ON provider_accounts(merchant_id, provider);

-- Only one primary account per provider per merchant
CREATE UNIQUE INDEX idx_provider_accounts_primary
    ON provider_accounts(merchant_id, provider)
    WHERE is_primary = true AND status = 'ACTIVE';
