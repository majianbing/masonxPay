CREATE TYPE card_settlement_report_status AS ENUM (
    'MATCHED',
    'EXCEPTION'
);

CREATE TYPE card_settlement_report_line_status AS ENUM (
    'MATCHED',
    'CLEARING_NOT_FOUND',
    'AMOUNT_MISMATCH',
    'CURRENCY_MISMATCH',
    'PROGRAM_MISMATCH',
    'MOVEMENT_TYPE_MISMATCH'
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_card_program_tenant_ref'
    ) THEN
        ALTER TABLE card_program
            ADD CONSTRAINT uq_card_program_tenant_ref UNIQUE (program_id, merchant_id, mode);
    END IF;
END $$;

CREATE TABLE card_settlement_report (
    report_id         VARCHAR(40) PRIMARY KEY,
    merchant_id       VARCHAR(64) NOT NULL,
    mode              va_mode NOT NULL,
    program_id        VARCHAR(32) NOT NULL REFERENCES card_program(program_id),
    issuer_partner_id VARCHAR(32) NOT NULL REFERENCES issuer_partner(issuer_partner_id),
    report_ref        VARCHAR(100) NOT NULL,
    settlement_date   DATE NOT NULL,
    currency          VARCHAR(20) NOT NULL,
    total_amount      NUMERIC(38, 8) NOT NULL,
    matched_amount    NUMERIC(38, 8) NOT NULL DEFAULT 0,
    exception_amount  NUMERIC(38, 8) NOT NULL DEFAULT 0,
    line_count        INTEGER NOT NULL DEFAULT 0,
    exception_count   INTEGER NOT NULL DEFAULT 0,
    status            card_settlement_report_status NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_card_settlement_report_ref UNIQUE (merchant_id, mode, issuer_partner_id, report_ref),
    CONSTRAINT fk_card_settlement_report_program_tenant
        FOREIGN KEY (program_id, merchant_id, mode)
        REFERENCES card_program (program_id, merchant_id, mode)
);

CREATE INDEX idx_card_settlement_report_program
    ON card_settlement_report (merchant_id, mode, program_id, settlement_date DESC);

CREATE TABLE card_settlement_report_line (
    report_line_id            VARCHAR(40) PRIMARY KEY,
    report_id                 VARCHAR(40) NOT NULL REFERENCES card_settlement_report(report_id),
    merchant_id               VARCHAR(64) NOT NULL,
    mode                      va_mode NOT NULL,
    program_id                VARCHAR(32) NOT NULL,
    rail_payment_id           VARCHAR(64) NOT NULL,
    issuer_transaction_id     VARCHAR(100),
    movement_type             VARCHAR(40) NOT NULL,
    amount                    NUMERIC(38, 8) NOT NULL,
    currency                  VARCHAR(20) NOT NULL,
    matched_clearing_event_id VARCHAR(40),
    matched_card_id           VARCHAR(32),
    status                    card_settlement_report_line_status NOT NULL,
    mismatch_amount           NUMERIC(38, 8) NOT NULL DEFAULT 0,
    detail                    VARCHAR(500),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_card_settlement_report_line_report
    ON card_settlement_report_line (report_id, status);

CREATE INDEX idx_card_settlement_report_line_program
    ON card_settlement_report_line (merchant_id, mode, program_id, status, created_at DESC);
