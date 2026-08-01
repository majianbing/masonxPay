-- FE6: gateway-owned fee schedules and immutable assessment snapshots.
-- This is an assess-only cutpoint for now; fee postings/settlement effects remain out of scope.

CREATE TABLE gateway_fee_schedule (
    schedule_id             VARCHAR(40) PRIMARY KEY,
    merchant_id             UUID NOT NULL,
    mode                    VARCHAR(10) NOT NULL,
    event_type              VARCHAR(60),
    provider                VARCHAR(40),
    connector_account_id    UUID,
    payment_method_type     VARCHAR(50),
    name                    VARCHAR(120) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_gateway_fee_schedule_tenant_ref
        UNIQUE (schedule_id, merchant_id, mode),
    CONSTRAINT chk_gateway_fee_schedule_mode
        CHECK (mode IN ('TEST', 'LIVE')),
    CONSTRAINT chk_gateway_fee_schedule_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_gateway_fee_schedule_name_not_blank
        CHECK (length(trim(name)) > 0)
);

CREATE INDEX idx_gateway_fee_schedule_scope
    ON gateway_fee_schedule (
        merchant_id, mode, event_type, provider, connector_account_id, payment_method_type, status
    );

CREATE TABLE gateway_fee_schedule_version (
    schedule_id     VARCHAR(40) NOT NULL,
    merchant_id     UUID NOT NULL,
    mode            VARCHAR(10) NOT NULL,
    version         INTEGER NOT NULL CHECK (version > 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    fee_currency    VARCHAR(20) NOT NULL,
    fee_scale       INTEGER NOT NULL CHECK (fee_scale >= 0),
    rounding_mode   VARCHAR(30) NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ,
    rules_json      JSONB NOT NULL,
    metadata_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    PRIMARY KEY (schedule_id, version),
    CONSTRAINT fk_gateway_fee_schedule_version_schedule
        FOREIGN KEY (schedule_id, merchant_id, mode)
        REFERENCES gateway_fee_schedule (schedule_id, merchant_id, mode),
    CONSTRAINT chk_gateway_fee_schedule_version_mode
        CHECK (mode IN ('TEST', 'LIVE')),
    CONSTRAINT chk_gateway_fee_schedule_version_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_gateway_fee_schedule_version_currency_not_blank
        CHECK (length(trim(fee_currency)) > 0),
    CONSTRAINT chk_gateway_fee_schedule_version_rules_array
        CHECK (jsonb_typeof(rules_json) = 'array'),
    CONSTRAINT chk_gateway_fee_schedule_version_metadata_object
        CHECK (jsonb_typeof(metadata_json) = 'object'),
    CONSTRAINT chk_gateway_fee_schedule_version_effective_window
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX uq_gateway_fee_schedule_one_active_version
    ON gateway_fee_schedule_version (schedule_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_gateway_fee_schedule_version_active
    ON gateway_fee_schedule_version (merchant_id, mode, status, effective_from, effective_to);

CREATE TABLE gateway_fee_assessment (
    assessment_id           VARCHAR(40) PRIMARY KEY,
    merchant_id             UUID NOT NULL,
    mode                    VARCHAR(10) NOT NULL,
    event_type              VARCHAR(60) NOT NULL,
    event_id                UUID NOT NULL,
    payment_intent_id       UUID,
    payment_request_id      UUID,
    provider                VARCHAR(40),
    connector_account_id    UUID,
    payment_method_type     VARCHAR(50),
    schedule_id             VARCHAR(40) NOT NULL,
    schedule_version        INTEGER NOT NULL,
    context_json            JSONB NOT NULL,
    matched_rules_json      JSONB NOT NULL,
    visible_totals_json     JSONB NOT NULL,
    hidden_totals_json      JSONB NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_gateway_fee_assessment_event
        UNIQUE (merchant_id, mode, event_type, event_id),
    CONSTRAINT uq_gateway_fee_assessment_tenant_ref
        UNIQUE (assessment_id, merchant_id, mode),
    CONSTRAINT fk_gateway_fee_assessment_schedule_version
        FOREIGN KEY (schedule_id, schedule_version)
        REFERENCES gateway_fee_schedule_version (schedule_id, version),
    CONSTRAINT chk_gateway_fee_assessment_mode
        CHECK (mode IN ('TEST', 'LIVE')),
    CONSTRAINT chk_gateway_fee_assessment_context_object
        CHECK (jsonb_typeof(context_json) = 'object'),
    CONSTRAINT chk_gateway_fee_assessment_matched_rules_array
        CHECK (jsonb_typeof(matched_rules_json) = 'array'),
    CONSTRAINT chk_gateway_fee_assessment_visible_totals_object
        CHECK (jsonb_typeof(visible_totals_json) = 'object'),
    CONSTRAINT chk_gateway_fee_assessment_hidden_totals_object
        CHECK (jsonb_typeof(hidden_totals_json) = 'object')
);

CREATE INDEX idx_gateway_fee_assessment_tenant_created
    ON gateway_fee_assessment (merchant_id, mode, created_at DESC);

CREATE INDEX idx_gateway_fee_assessment_payment_intent
    ON gateway_fee_assessment (merchant_id, mode, payment_intent_id);

CREATE TABLE gateway_fee_assessment_line (
    line_id                 BIGSERIAL PRIMARY KEY,
    assessment_id           VARCHAR(40) NOT NULL,
    merchant_id             UUID NOT NULL,
    mode                    VARCHAR(10) NOT NULL,
    rule_id                 VARCHAR(80) NOT NULL,
    rule_version            INTEGER NOT NULL CHECK (rule_version > 0),
    rule_name               VARCHAR(120) NOT NULL,
    component_id            VARCHAR(80) NOT NULL,
    name                    VARCHAR(120) NOT NULL,
    visibility              VARCHAR(30) NOT NULL,
    currency                VARCHAR(20) NOT NULL,
    basis_amount            NUMERIC(38, 8),
    raw_calculated_amount   NUMERIC(38, 8) NOT NULL,
    amount                  NUMERIC(38, 8) NOT NULL,
    rounding_mode           VARCHAR(30) NOT NULL,
    rounding_scale          INTEGER NOT NULL CHECK (rounding_scale >= 0),
    metadata_json           JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_gateway_fee_assessment_line_mode
        CHECK (mode IN ('TEST', 'LIVE')),
    CONSTRAINT chk_gateway_fee_assessment_line_metadata_object
        CHECK (jsonb_typeof(metadata_json) = 'object'),
    CONSTRAINT fk_gateway_fee_assessment_line_assessment_tenant
        FOREIGN KEY (assessment_id, merchant_id, mode)
        REFERENCES gateway_fee_assessment (assessment_id, merchant_id, mode)
);

CREATE INDEX idx_gateway_fee_assessment_line_assessment
    ON gateway_fee_assessment_line (merchant_id, mode, assessment_id, line_id);
