-- Phase MR: add rail-specific account types and virtual card entity.
--
-- ALTER TYPE ... ADD VALUE cannot run inside a transaction block in Postgres,
-- so each statement must be committed individually. Flyway handles this when
-- outOfOrder=false and each migration runs in its own connection.

ALTER TYPE va_account_type ADD VALUE IF NOT EXISTS 'PREPAID_CARD';
ALTER TYPE va_account_type ADD VALUE IF NOT EXISTS 'CARD_NETWORK_RECEIVABLE';
ALTER TYPE va_account_type ADD VALUE IF NOT EXISTS 'BANK_RAIL_RECEIVABLE';
ALTER TYPE va_account_type ADD VALUE IF NOT EXISTS 'SUSPENSE_UNKNOWN_TXN';

-- virtual_card: links a test card PAN token to a PREPAID_CARD VaAccount.
-- Lifecycle: ACTIVE -> FROZEN | EXPIRED | CLOSED.
-- Closing sweeps remaining balance back to the owner WALLET account.

CREATE TYPE va_virtual_card_status AS ENUM ('ACTIVE', 'FROZEN', 'EXPIRED', 'CLOSED');

CREATE TABLE virtual_card (
    card_id           VARCHAR(32)               PRIMARY KEY,
    masked_pan        VARCHAR(20)               NOT NULL,   -- e.g. 4111****1234
    bin               VARCHAR(10)               NOT NULL,   -- BIN prefix for network routing
    vcc_account_id    VARCHAR(32)               NOT NULL REFERENCES va_account (account_id),
    owner_account_id  VARCHAR(32)               NOT NULL REFERENCES va_account (account_id),
    status            va_virtual_card_status    NOT NULL DEFAULT 'ACTIVE',
    spending_limit    NUMERIC(38, 8),                       -- optional cap below loaded balance
    currency          VARCHAR(3)                NOT NULL,
    expiry            DATE,
    created_at        TIMESTAMPTZ               NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ               NOT NULL DEFAULT now()
);

CREATE INDEX idx_virtual_card_owner   ON virtual_card (owner_account_id);
CREATE INDEX idx_virtual_card_vcc     ON virtual_card (vcc_account_id);
CREATE INDEX idx_virtual_card_bin     ON virtual_card (bin, status)
    WHERE status = 'ACTIVE';
