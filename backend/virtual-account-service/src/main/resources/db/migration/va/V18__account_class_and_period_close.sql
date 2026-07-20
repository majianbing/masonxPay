-- Formal chart-of-accounts class and accounting-period close controls.
--
-- account_class is the financial-statement class from the platform-books
-- perspective. ledger_account_type remains the product/business subtype.
--
-- accounting_period is platform-scoped today. merchant_id is present to keep
-- tenant-scope shape on every table and to leave room for merchant-specific
-- close calendars later; current platform-wide rows use merchant_id='PLATFORM'.

CREATE TYPE va_account_class AS ENUM ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE');
CREATE TYPE accounting_period_status AS ENUM ('OPEN', 'CLOSED');

ALTER TABLE ledger_account
    ADD COLUMN IF NOT EXISTS account_class va_account_class;

UPDATE ledger_account
SET account_class = CASE
    WHEN ledger_account_type IN (
        'WALLET',
        'CREDIT_LINE',
        'PREPAID_CARD',
        'PREPAID_CARD_HOLD',
        'CARD_NETWORK_RECEIVABLE',
        'CLEARING',
        'SUSPENSE'
    ) THEN 'LIABILITY'::va_account_class
    WHEN ledger_account_type = 'FEE_INCOME' THEN 'REVENUE'::va_account_class
    WHEN ledger_account_type = 'BAD_DEBT' THEN 'EXPENSE'::va_account_class
    ELSE 'ASSET'::va_account_class
END
WHERE account_class IS NULL;

ALTER TABLE ledger_account
    ALTER COLUMN account_class SET NOT NULL;

CREATE TABLE accounting_period (
    accounting_period_id VARCHAR(40) PRIMARY KEY,
    merchant_id          VARCHAR(64) NOT NULL,
    mode                 va_mode     NOT NULL,
    asset                VARCHAR(20) NOT NULL,
    period_start         DATE        NOT NULL,
    period_end           DATE        NOT NULL,
    status               accounting_period_status NOT NULL DEFAULT 'OPEN',
    closed_at            TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_accounting_period_bounds CHECK (period_start <= period_end),
    CONSTRAINT chk_accounting_period_closed_at
        CHECK (status != 'CLOSED' OR closed_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_accounting_period_scope
    ON accounting_period (merchant_id, mode, asset, period_start, period_end);

CREATE INDEX idx_accounting_period_lookup
    ON accounting_period (merchant_id, mode, asset, period_start, period_end);
