-- Replace provider-level routing with account-level routing.
-- Rules now point to a specific connector account instead of a provider brand,
-- enabling precise traffic control across multiple accounts of the same provider.

ALTER TABLE routing_rules
    DROP COLUMN target_provider,
    DROP COLUMN fallback_provider,
    ADD COLUMN target_account_id  UUID NOT NULL REFERENCES provider_accounts(id),
    ADD COLUMN fallback_account_id UUID REFERENCES provider_accounts(id);
