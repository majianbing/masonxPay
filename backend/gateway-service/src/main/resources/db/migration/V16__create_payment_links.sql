CREATE TABLE payment_links (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id  UUID         NOT NULL REFERENCES merchants(id),
    token        VARCHAR(64)  NOT NULL UNIQUE,
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    amount       BIGINT       NOT NULL,       -- in cents
    currency     VARCHAR(10)  NOT NULL DEFAULT 'usd',
    mode         VARCHAR(10)  NOT NULL DEFAULT 'TEST',
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | INACTIVE
    redirect_url VARCHAR(500),                -- optional post-payment redirect
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ                  -- null = never expires
);

CREATE INDEX idx_payment_links_merchant_mode ON payment_links(merchant_id, mode);
CREATE INDEX idx_payment_links_token         ON payment_links(token);
