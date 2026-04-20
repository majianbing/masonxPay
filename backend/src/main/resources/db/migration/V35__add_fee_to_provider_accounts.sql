-- Phase 3.5: Cost-aware routing — fee configuration per connector account
--
-- fixed_fee_cents: flat per-transaction fee in the smallest currency unit (e.g. 30 = $0.30)
-- rate_bps:        percentage rate in basis points (e.g. 290 = 2.90%)
--
-- Effective cost on a transaction = fixed_fee_cents + (amount * rate_bps / 10000)
-- Both default to 0 so existing connectors are unaffected (no cost filter applied).

ALTER TABLE provider_accounts
    ADD COLUMN fixed_fee_cents INT NOT NULL DEFAULT 0,
    ADD COLUMN rate_bps        INT NOT NULL DEFAULT 0;
