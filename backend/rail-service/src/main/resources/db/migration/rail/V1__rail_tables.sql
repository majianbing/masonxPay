-- Phase MR baseline: all rail-service tables.
-- rail-service uses its own database (msx_rail) and does NOT use ShardingSphere.

CREATE TYPE rail_type        AS ENUM ('CARD_ISO8583', 'BANK_ISO20022');
CREATE TYPE rail_movement    AS ENUM (
    'CARD_AUTH', 'CARD_CAPTURE', 'CARD_SALE', 'CARD_REVERSAL', 'CARD_REFUND', 'CARD_CREDIT',
    'BANK_CREDIT_TRANSFER', 'BANK_RETURN', 'BANK_STATUS_INQUIRY'
);
CREATE TYPE rail_status AS ENUM (
    'CREATED', 'ROUTING', 'SUBMITTED_TO_RAIL',
    'APPROVED', 'DECLINED',
    'ACCEPTED', 'PENDING',
    'UNKNOWN', 'REVERSAL_REQUIRED', 'REVERSAL_SENT', 'REVERSED',
    'RETURNED', 'FAILED',
    'SETTLED', 'RECONCILED', 'RECON_EXCEPTION'
);
CREATE TYPE rail_direction   AS ENUM ('SEND', 'RECV');
CREATE TYPE reversal_status  AS ENUM ('PENDING', 'SENT', 'RESOLVED', 'EXHAUSTED');
CREATE TYPE bank_return_status AS ENUM ('PENDING', 'MATCHED', 'UNMATCHED');

-- ── rail_payment ─────────────────────────────────────────────────────────────
-- Canonical payment record. Created before the adapter is invoked.
CREATE TABLE rail_payment (
    payment_id           VARCHAR(32)      PRIMARY KEY,
    merchant_id          VARCHAR(64)      NOT NULL,
    rail                 rail_type        NOT NULL,
    network              VARCHAR(30)      NOT NULL,   -- VISA_SIM, MC_SIM, SEPA_SIM, FEDNOW_SIM
    movement_type        rail_movement    NOT NULL,
    amount               NUMERIC(38, 8)   NOT NULL,
    currency             VARCHAR(3)       NOT NULL,
    status               rail_status      NOT NULL DEFAULT 'CREATED',
    idempotency_key      VARCHAR(128)     UNIQUE,
    original_payment_id  VARCHAR(32),
    version              INT              NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_rail_payment_merchant ON rail_payment (merchant_id, created_at DESC);
CREATE INDEX idx_rail_payment_status   ON rail_payment (status, updated_at DESC);

-- ── rail_routing_decision ────────────────────────────────────────────────────
-- Persisted before the adapter executes so routing is always auditable.
CREATE TABLE rail_routing_decision (
    id           VARCHAR(32)  PRIMARY KEY,
    payment_id   VARCHAR(32)  NOT NULL REFERENCES rail_payment (payment_id),
    rail         rail_type    NOT NULL,
    network      VARCHAR(30)  NOT NULL,
    adapter      VARCHAR(80)  NOT NULL,
    decided_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── rail_iso8583_log ─────────────────────────────────────────────────────────
-- Masked ISO 8583 message log. DE2 (PAN) is always masked before insert.
CREATE TABLE rail_iso8583_log (
    id              VARCHAR(32)      PRIMARY KEY,
    payment_id      VARCHAR(32)      NOT NULL,
    direction       rail_direction   NOT NULL,
    network         VARCHAR(30)      NOT NULL,
    mti             VARCHAR(4)       NOT NULL,
    stan            VARCHAR(6),
    rrn             VARCHAR(12),
    correlation_key VARCHAR(128),
    masked_de2      VARCHAR(20),                  -- e.g. 4111****1234
    response_code   VARCHAR(2),
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_rail_iso8583_payment ON rail_iso8583_log (payment_id, created_at);

-- ── rail_iso20022_log ────────────────────────────────────────────────────────
-- ISO 20022 message log. Raw XML is never stored; only structured metadata.
CREATE TABLE rail_iso20022_log (
    id                VARCHAR(32)      PRIMARY KEY,
    payment_id        VARCHAR(32)      NOT NULL,
    direction         rail_direction   NOT NULL,
    network           VARCHAR(30)      NOT NULL,
    message_name      VARCHAR(20)      NOT NULL,   -- pain.001, pacs.008, camt.054, etc.
    message_id        VARCHAR(64),
    instruction_id    VARCHAR(64),
    end_to_end_id     VARCHAR(64),
    transaction_id    VARCHAR(64),
    status_code       VARCHAR(10),
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_rail_iso20022_payment ON rail_iso20022_log (payment_id, created_at);

-- ── rail_network_correlation ─────────────────────────────────────────────────
-- Composite correlation key linking internal payment ID to network-assigned IDs.
-- STAN + RRN alone are not globally unique; the composite key is.
CREATE TABLE rail_network_correlation (
    id                      VARCHAR(32)  PRIMARY KEY,
    payment_id              VARCHAR(32)  NOT NULL,
    rail                    rail_type    NOT NULL,
    network                 VARCHAR(30)  NOT NULL,
    correlation_key         VARCHAR(128) UNIQUE,   -- {network}:{acq_id}:{stan}:{rrn}:{date}
    stan                    VARCHAR(6),
    rrn                     VARCHAR(12),
    iso20022_message_id     VARCHAR(64),
    iso20022_end_to_end_id  VARCHAR(64),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rail_correlation_payment ON rail_network_correlation (payment_id);

-- ── rail_reversal_task ───────────────────────────────────────────────────────
-- Tracks pending reversals for UNKNOWN state card transactions.
-- Created immediately when a card auth/sale times out.
CREATE TABLE rail_reversal_task (
    id               VARCHAR(32)      PRIMARY KEY,
    payment_id       VARCHAR(32)      NOT NULL REFERENCES rail_payment (payment_id),
    status           reversal_status  NOT NULL DEFAULT 'PENDING',
    attempts         INT              NOT NULL DEFAULT 0,
    max_attempts     INT              NOT NULL DEFAULT 3,
    next_attempt_at  TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_reversal_task_due ON rail_reversal_task (next_attempt_at)
    WHERE status = 'PENDING';

-- ── rail_bank_return_task ────────────────────────────────────────────────────
-- Tracks pacs.004 return messages received for bank rail payments.
CREATE TABLE rail_bank_return_task (
    id                   VARCHAR(32)        PRIMARY KEY,
    payment_id           VARCHAR(32)        NOT NULL REFERENCES rail_payment (payment_id),
    original_payment_id  VARCHAR(32),
    end_to_end_id        VARCHAR(64),
    status               bank_return_status NOT NULL DEFAULT 'PENDING',
    created_at           TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ        NOT NULL DEFAULT now()
);
