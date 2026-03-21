-- Generalize credential storage so Square/Adyen/PayPal/etc. can all be stored
-- without forcing every provider into a Stripe-shaped "secret_key + publishable_key" pair.
--
--   encrypted_credentials: JSON blob (AES-256-GCM) holding secret fields only.
--     Stripe  → {"secretKey":"sk_..."}
--     Square  → {"accessToken":"EAAA..."}
--
--   provider_config: JSON blob (plaintext) holding public / config identifiers.
--     Stripe  → {"publishableKey":"pk_..."}
--     Square  → {"applicationId":"sandbox-sq0idb-...","locationId":"L..."}

ALTER TABLE provider_accounts
    ADD COLUMN encrypted_credentials TEXT,
    ADD COLUMN provider_config        TEXT;

-- Old columns kept for backward-compat (existing Stripe connectors still use them).
-- New connectors use encrypted_credentials + provider_config instead.
ALTER TABLE provider_accounts
    ALTER COLUMN encrypted_secret_key DROP NOT NULL,
    ALTER COLUMN secret_key_hint      DROP NOT NULL;

-- Register Square as a known provider alongside STRIPE and PAYPAL.
COMMENT ON COLUMN provider_accounts.provider IS 'STRIPE | SQUARE | PAYPAL | ADYEN | ...';
