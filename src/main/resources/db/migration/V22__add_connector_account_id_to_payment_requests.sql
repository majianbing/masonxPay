-- Track which specific connector account was used for each payment attempt.
-- Nullable so existing rows are unaffected; populated for all new attempts.
ALTER TABLE payment_requests
    ADD COLUMN connector_account_id UUID;
