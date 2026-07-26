-- Phase PPC1: prepaid card program baseline.
--
-- These tables introduce the merchant/mode-scoped program-manager model above
-- the existing simulator-backed VCC foundation. They do not change card
-- creation behavior yet; PPC2 will route card creation through issuer adapters.

CREATE TYPE issuer_partner_type AS ENUM (
    'RAIL_SIM',
    'MARQETA',
    'LITHIC',
    'ADYEN_ISSUING',
    'STRIPE_ISSUING'
);

CREATE TYPE issuer_partner_status AS ENUM (
    'DRAFT',
    'ACTIVE',
    'SUSPENDED',
    'CLOSED'
);

CREATE TYPE card_program_status AS ENUM (
    'DRAFT',
    'ACTIVE',
    'SUSPENDED',
    'CLOSED'
);

CREATE TYPE card_program_system_of_record AS ENUM (
    'INTERNAL',
    'EXTERNAL'
);

CREATE TYPE card_program_funding_model AS ENUM (
    'PREFUNDED_CARD_BALANCE',
    'PROGRAM_BALANCE',
    'JIT_FUNDING',
    'EXTERNAL_ISSUER_BALANCE',
    'SIMULATED'
);

CREATE TYPE cardholder_type AS ENUM (
    'INDIVIDUAL',
    'BUSINESS',
    'EMPLOYEE',
    'SERVICE_ACCOUNT'
);

CREATE TYPE cardholder_kyc_status AS ENUM (
    'PENDING',
    'ACTIVE',
    'REJECTED',
    'SUSPENDED',
    'CLOSED'
);

CREATE TABLE issuer_partner (
    issuer_partner_id       VARCHAR(32)             PRIMARY KEY,
    merchant_id             VARCHAR(64)             NOT NULL,
    mode                    va_mode                 NOT NULL,
    name                    VARCHAR(120)            NOT NULL,
    adapter_type            issuer_partner_type     NOT NULL,
    status                  issuer_partner_status   NOT NULL DEFAULT 'DRAFT',

    -- Pointers only: no raw credentials, PAN, CVV, webhook secrets, or issuer
    -- payloads belong in this table.
    credentials_ref         VARCHAR(200),
    config_json             JSONB                   NOT NULL DEFAULT '{}'::jsonb,
    webhook_secret_ref      VARCHAR(200),

    external_program_id     VARCHAR(100),
    external_funding_source_id VARCHAR(100),

    created_at              TIMESTAMPTZ             NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ             NOT NULL DEFAULT now(),

    CONSTRAINT chk_issuer_partner_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT uq_issuer_partner_tenant_ref UNIQUE (issuer_partner_id, merchant_id, mode)
);

CREATE INDEX idx_issuer_partner_tenant
    ON issuer_partner (merchant_id, mode, status);

CREATE UNIQUE INDEX uq_issuer_partner_external_program
    ON issuer_partner (merchant_id, mode, adapter_type, external_program_id)
    WHERE external_program_id IS NOT NULL;

CREATE TABLE card_program (
    program_id              VARCHAR(32)                     PRIMARY KEY,
    merchant_id             VARCHAR(64)                     NOT NULL,
    mode                    va_mode                         NOT NULL,
    issuer_partner_id       VARCHAR(32)                     NOT NULL
        REFERENCES issuer_partner (issuer_partner_id),
    name                    VARCHAR(120)                    NOT NULL,
    currency                VARCHAR(20)                     NOT NULL,
    bin_range_metadata      JSONB                           NOT NULL DEFAULT '{}'::jsonb,
    status                  card_program_status             NOT NULL DEFAULT 'DRAFT',
    system_of_record        card_program_system_of_record   NOT NULL,
    funding_model           card_program_funding_model      NOT NULL,
    feature_flags_json      JSONB                           NOT NULL DEFAULT '{}'::jsonb,
    default_controls_json   JSONB                           NOT NULL DEFAULT '{}'::jsonb,
    settlement_model_json   JSONB                           NOT NULL DEFAULT '{}'::jsonb,
    fee_schedule_id         VARCHAR(64),

    external_program_id     VARCHAR(100),
    external_funding_source_id VARCHAR(100),

    created_at              TIMESTAMPTZ                     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ                     NOT NULL DEFAULT now(),

    CONSTRAINT chk_card_program_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_card_program_currency_not_blank CHECK (length(trim(currency)) > 0),
    CONSTRAINT fk_card_program_issuer_partner_tenant
        FOREIGN KEY (issuer_partner_id, merchant_id, mode)
        REFERENCES issuer_partner (issuer_partner_id, merchant_id, mode)
);

CREATE INDEX idx_card_program_tenant
    ON card_program (merchant_id, mode, status);

CREATE INDEX idx_card_program_issuer_partner
    ON card_program (issuer_partner_id);

CREATE UNIQUE INDEX uq_card_program_external_program
    ON card_program (merchant_id, mode, issuer_partner_id, external_program_id)
    WHERE external_program_id IS NOT NULL;

CREATE TABLE cardholder (
    cardholder_id              VARCHAR(32)            PRIMARY KEY,
    merchant_id                VARCHAR(64)            NOT NULL,
    mode                       va_mode                NOT NULL,
    type                       cardholder_type        NOT NULL,
    external_issuer_cardholder_id VARCHAR(100),
    kyc_status                 cardholder_kyc_status  NOT NULL DEFAULT 'PENDING',

    -- Minimal display/profile references only. Store sensitive PII in a future
    -- dedicated identity/KYC boundary, not in VA ledger tables.
    display_name               VARCHAR(120),
    display_ref                VARCHAR(120),
    profile_ref                VARCHAR(200),

    created_at                 TIMESTAMPTZ            NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ            NOT NULL DEFAULT now()
);

CREATE INDEX idx_cardholder_tenant
    ON cardholder (merchant_id, mode, kyc_status);

CREATE UNIQUE INDEX uq_cardholder_external_issuer_id
    ON cardholder (merchant_id, mode, external_issuer_cardholder_id)
    WHERE external_issuer_cardholder_id IS NOT NULL;
