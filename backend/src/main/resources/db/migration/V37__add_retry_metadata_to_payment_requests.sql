-- Retry orchestration metadata.
--
-- provider_idempotency_key is intentionally persisted so same-account retries can
-- reuse the exact same provider idempotency key, avoiding duplicate charges when
-- the first attempt outcome is unknown.

ALTER TABLE payment_requests
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN attempt_type VARCHAR(30) NOT NULL DEFAULT 'PRIMARY',
    ADD COLUMN provider_idempotency_key VARCHAR(255);

CREATE INDEX idx_payment_requests_intent_attempt
    ON payment_requests(payment_intent_id, attempt_number);
