-- ledger_account: generic double-entry ledger account.
--
-- One primitive covers every scenario (CASH, WALLET, CREDIT_LINE, RESERVE,
-- RECEIVABLE, platform books, external mirrors). Business semantics live above
-- this table as per-type policy; the core is asset- and semantics-agnostic.
--
-- Design: docs/engineering/virtual-account-guide.md

CREATE TYPE ledger_account_role   AS ENUM ('TENANT', 'PLATFORM', 'EXTERNAL');
CREATE TYPE ledger_account_type   AS ENUM (
    'CASH', 'WALLET', 'CREDIT_LINE',
    'RECEIVABLE', 'RESERVE',
    'FEE_INCOME', 'CLEARING', 'SUSPENSE', 'BAD_DEBT'
);
CREATE TYPE va_asset_class    AS ENUM ('FIAT', 'CRYPTO');
CREATE TYPE va_normal_balance AS ENUM ('DEBIT', 'CREDIT');
CREATE TYPE ledger_account_status AS ENUM ('ACTIVE', 'FROZEN', 'CLOSED');
CREATE TYPE va_mode           AS ENUM ('TEST', 'LIVE');

CREATE TABLE ledger_account (
    ledger_account_id      VARCHAR(32)        PRIMARY KEY,  -- snowflake: ac_{id}
    mode            va_mode            NOT NULL,
    ledger_account_role    ledger_account_role    NOT NULL,

    -- Ownership (TENANT only)
    org_id          VARCHAR(64),
    merchant_id     VARCHAR(64),

    -- External mirror (EXTERNAL only)
    provider_id     VARCHAR(64),

    ledger_account_type    ledger_account_type    NOT NULL,
    asset           VARCHAR(20)        NOT NULL,      -- e.g. USD, BTC, USDC
    asset_class     va_asset_class     NOT NULL,
    scale           SMALLINT           NOT NULL,      -- decimal places: FIAT=2, CRYPTO=8

    normal_balance  va_normal_balance  NOT NULL,

    -- Materialized balance — updated atomically with every ledger entry.
    balance         NUMERIC(38, 8)     NOT NULL DEFAULT 0,

    status          ledger_account_status  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ        NOT NULL DEFAULT now(),

    CONSTRAINT chk_tenant_scope
        CHECK (ledger_account_role != 'TENANT' OR (merchant_id IS NOT NULL)),
    CONSTRAINT chk_external_scope
        CHECK (ledger_account_role != 'EXTERNAL' OR provider_id IS NOT NULL),
    CONSTRAINT chk_balance_non_negative
        CHECK (balance >= 0)
);

-- Tenant account lookups (merchant dashboard, settlement routing)
CREATE INDEX idx_ledger_account_tenant ON ledger_account (mode, merchant_id)
    WHERE ledger_account_role = 'TENANT';

-- External mirror lookups (provider reconciliation)
CREATE INDEX idx_ledger_account_external ON ledger_account (provider_id)
    WHERE ledger_account_role = 'EXTERNAL';

-- Type-specific extension tables (class-table inheritance).
-- Type-specific columns live ONLY here — never on ledger_account.

CREATE TABLE va_credit_line_profile (
    ledger_account_id   VARCHAR(32) PRIMARY KEY REFERENCES ledger_account (ledger_account_id),
    credit_limit NUMERIC(38, 8) NOT NULL,
    billing_cycle_days INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE va_wallet_profile (
    ledger_account_id  VARCHAR(32) PRIMARY KEY REFERENCES ledger_account (ledger_account_id),
    chain       VARCHAR(50),
    address     VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE va_cash_profile (
    ledger_account_id    VARCHAR(32) PRIMARY KEY REFERENCES ledger_account (ledger_account_id),
    payout_config JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
