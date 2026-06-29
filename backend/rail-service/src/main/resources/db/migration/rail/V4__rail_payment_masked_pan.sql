-- MR4: store the masked card PAN on the payment record so settlement event
-- publishers can identify the card product (BIN 999999 = VA-issued prepaid card)
-- without re-querying the ISO 8583 log. Null for bank transfers.
ALTER TABLE rail_payment ADD COLUMN IF NOT EXISTS masked_pan VARCHAR(20);
