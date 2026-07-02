-- Some payment_read_models rows can predate external_id support in the projection
-- backfill. Populate missing read-model public IDs from the authoritative payment
-- intent shards so dashboard list/overview responses can display pi_... IDs.

DO $$
DECLARE
    i INT;
    suffix TEXT;
BEGIN
    FOR i IN 0..63 LOOP
        suffix := lpad(i::text, 2, '0');

        EXECUTE format('
            UPDATE payment_read_models prm
            SET external_id = pi.external_id,
                search_text = lower(concat_ws('' '',
                    prm.payment_intent_id::text,
                    pi.external_id,
                    prm.provider_payment_id,
                    prm.order_id,
                    prm.description,
                    prm.billing_email,
                    prm.resolved_provider,
                    prm.last_refund_id::text
                )),
                updated_at = now()
            FROM payment_intents_%1$s pi
            WHERE prm.payment_intent_id = pi.id
              AND prm.external_id IS NULL
              AND pi.external_id IS NOT NULL
        ', suffix);
    END LOOP;
END $$;

UPDATE payment_read_models prm
SET external_id = pi.external_id,
    search_text = lower(concat_ws(' ',
        prm.payment_intent_id::text,
        pi.external_id,
        prm.provider_payment_id,
        prm.order_id,
        prm.description,
        prm.billing_email,
        prm.resolved_provider,
        prm.last_refund_id::text
    )),
    updated_at = now()
FROM payment_intents pi
WHERE prm.payment_intent_id = pi.id
  AND prm.external_id IS NULL
  AND pi.external_id IS NOT NULL;
