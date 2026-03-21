-- Add weight to provider_accounts for weighted round-robin account selection
ALTER TABLE provider_accounts ADD COLUMN weight INTEGER NOT NULL DEFAULT 1;

-- Payment tokens: short-lived opaque tokens that map gw_tok_xxx -> provider PM ID
-- Created by /pub/tokenize, consumed by /pub/pay/{token}/checkout or /api/v1/payments
CREATE TABLE payment_tokens (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    provider        VARCHAR(30) NOT NULL,
    account_id      UUID        NOT NULL REFERENCES provider_accounts(id),
    provider_pm_id  TEXT        NOT NULL,   -- e.g. pm_3P... from Stripe.js — never exposed
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,            -- set on first use; second use rejected
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_tokens_merchant ON payment_tokens(merchant_id);
CREATE INDEX idx_payment_tokens_expires  ON payment_tokens(expires_at);
