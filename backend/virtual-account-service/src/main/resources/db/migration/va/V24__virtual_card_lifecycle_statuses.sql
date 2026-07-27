-- Phase PPC3: prepaid card lifecycle statuses.
--
-- FROZEN remains for legacy rows/API compatibility. New lifecycle APIs use
-- LOCKED for reversible operational locks and TERMINATED for irreversible
-- issuer-side termination.

ALTER TYPE va_virtual_card_status ADD VALUE IF NOT EXISTS 'CREATED';
ALTER TYPE va_virtual_card_status ADD VALUE IF NOT EXISTS 'LOCKED';
ALTER TYPE va_virtual_card_status ADD VALUE IF NOT EXISTS 'SUSPENDED';
ALTER TYPE va_virtual_card_status ADD VALUE IF NOT EXISTS 'CLOSING';
ALTER TYPE va_virtual_card_status ADD VALUE IF NOT EXISTS 'TERMINATED';
