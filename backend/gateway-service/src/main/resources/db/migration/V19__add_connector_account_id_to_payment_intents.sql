-- Store the exact connector account used for each payment.
-- Refunds, chargebacks, and captures must use the same account that charged the card.
ALTER TABLE payment_intents
    ADD COLUMN connector_account_id UUID REFERENCES provider_accounts(id);
