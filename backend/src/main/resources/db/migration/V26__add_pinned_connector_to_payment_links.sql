-- Allows a payment link to be scoped to a single connector account.
-- Used by the connector preview feature: the SDK only shows that one provider,
-- and routing bypasses the normal weighted engine in favour of this account.
ALTER TABLE payment_links ADD COLUMN pinned_connector_id UUID;
