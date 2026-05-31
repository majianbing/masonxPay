ALTER TABLE payment_instruments
    ADD COLUMN provider_customer_reference VARCHAR(255);

CREATE INDEX idx_payment_instruments_provider_customer_reference
    ON payment_instruments (merchant_id, provider, provider_customer_reference);
