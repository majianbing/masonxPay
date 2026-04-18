-- Phase 3.3: 3DS / SCA challenge support
-- Stores the provider action required before a payment can be finalized.
-- Populated when a charge returns requires_action (e.g. Stripe 3DS2 or 3DS1 redirect).

ALTER TABLE payment_intents
    ADD COLUMN action_type VARCHAR(30),     -- "stripe_sdk" | "redirect_url"
    ADD COLUMN action_url  VARCHAR(2000);   -- redirect URL for redirect_url type; NULL for stripe_sdk
