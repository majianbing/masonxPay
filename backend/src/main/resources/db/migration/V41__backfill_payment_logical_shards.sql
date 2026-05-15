-- Local/demo backfill for H1 logical payment sharding.
--
-- This copies historical rows from the original payment_intents/payment_requests
-- tables into payment_intents_00..63 and payment_requests_00..63. It is
-- idempotent for local/test rebuilds. Production migration should use an online
-- dual-write + batch backfill + validation process rather than one Flyway step.

CREATE OR REPLACE FUNCTION mx_payment_uuid_shard(value UUID)
RETURNS INTEGER
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    bytes BYTEA := uuid_send(value);
    hash BIGINT;
BEGIN
    -- Java UUID.hashCode():
    -- (int)((mostSigBits >>> 32) ^ mostSigBits ^ (leastSigBits >>> 32) ^ leastSigBits)
    hash :=
        (((get_byte(bytes, 0)::BIGINT << 24) | (get_byte(bytes, 1)::BIGINT << 16) |
          (get_byte(bytes, 2)::BIGINT << 8)  |  get_byte(bytes, 3)::BIGINT) #
         ((get_byte(bytes, 4)::BIGINT << 24) | (get_byte(bytes, 5)::BIGINT << 16) |
          (get_byte(bytes, 6)::BIGINT << 8)  |  get_byte(bytes, 7)::BIGINT) #
         ((get_byte(bytes, 8)::BIGINT << 24) | (get_byte(bytes, 9)::BIGINT << 16) |
          (get_byte(bytes, 10)::BIGINT << 8) |  get_byte(bytes, 11)::BIGINT) #
         ((get_byte(bytes, 12)::BIGINT << 24) | (get_byte(bytes, 13)::BIGINT << 16) |
          (get_byte(bytes, 14)::BIGINT << 8)  |  get_byte(bytes, 15)::BIGINT));

    -- floorMod(hash, 64); with a power-of-two divisor this is the low 6 bits.
    RETURN (hash & 63)::INTEGER;
END $$;

DO $$
DECLARE
    i INT;
    suffix TEXT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');

        EXECUTE format('
            INSERT INTO payment_intents_%s (
                id, merchant_id, mode, amount, currency, status, capture_method,
                idempotency_key, resolved_provider, connector_account_id,
                provider_payment_id, provider_response, metadata,
                success_url, cancel_url, failure_url, payment_method_type,
                order_id, description, billing_details, shipping_details,
                trace_id, action_type, action_url, expires_at, created_at, updated_at
            )
            SELECT
                id, merchant_id, mode, amount, currency, status, capture_method,
                idempotency_key, resolved_provider, connector_account_id,
                provider_payment_id, provider_response, metadata,
                success_url, cancel_url, failure_url, payment_method_type,
                order_id, description, billing_details, shipping_details,
                trace_id, action_type, action_url, expires_at, created_at, updated_at
            FROM payment_intents
            WHERE mx_payment_uuid_shard(id) = %s
            ON CONFLICT (id) DO NOTHING', suffix, i);

        EXECUTE format('
            INSERT INTO payment_requests_%s (
                id, merchant_id, payment_intent_id, amount, currency,
                payment_method_type, status, provider_request_id, provider_response,
                failure_code, failure_message, connector_account_id,
                attempt_number, attempt_type, provider_idempotency_key,
                created_at, updated_at
            )
            SELECT
                pr.id, pi.merchant_id, pr.payment_intent_id, pr.amount, pr.currency,
                pr.payment_method_type, pr.status, pr.provider_request_id, pr.provider_response,
                pr.failure_code, pr.failure_message, pr.connector_account_id,
                pr.attempt_number, pr.attempt_type, pr.provider_idempotency_key,
                pr.created_at, pr.updated_at
            FROM payment_requests pr
            JOIN payment_intents pi ON pi.id = pr.payment_intent_id
            WHERE mx_payment_uuid_shard(pr.payment_intent_id) = %s
            ON CONFLICT (id) DO NOTHING', suffix, i);
    END LOOP;
END $$;

DROP FUNCTION mx_payment_uuid_shard(UUID);
