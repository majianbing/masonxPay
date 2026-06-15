-- Phase 4.5 — Disputes / Chargebacks

CREATE TABLE disputes (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id           UUID,               -- null when the linked payment intent cannot be resolved
    payment_intent_id     UUID,               -- FK to payment_intents (nullable — disputes may arrive before intent sync)
    provider              VARCHAR(20)  NOT NULL,
    provider_dispute_id   VARCHAR(255) NOT NULL,
    provider_charge_id    VARCHAR(255),       -- PSP charge/payment ID used for lookup
    status                VARCHAR(30)  NOT NULL,
    reason                VARCHAR(60),
    amount                BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    evidence_due_by       TIMESTAMP,
    submitted_at          TIMESTAMP,          -- set when merchant submits evidence via MasonXPay
    resolved_at           TIMESTAMP,
    evidence_text_json    TEXT,               -- JSON snapshot of last submitted text evidence
    mode                  VARCHAR(10)  NOT NULL DEFAULT 'LIVE',
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT uq_disputes_provider_dispute_id UNIQUE (provider_dispute_id)
);

CREATE INDEX idx_disputes_merchant_id        ON disputes (merchant_id);
CREATE INDEX idx_disputes_payment_intent_id  ON disputes (payment_intent_id);
CREATE INDEX idx_disputes_status             ON disputes (merchant_id, status);
CREATE INDEX idx_disputes_evidence_due_by    ON disputes (evidence_due_by) WHERE status = 'NEEDS_RESPONSE';

CREATE TABLE dispute_evidence_files (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispute_id   UUID         NOT NULL REFERENCES disputes(id) ON DELETE CASCADE,
    merchant_id  UUID         NOT NULL,
    file_key     VARCHAR(500) NOT NULL,
    file_name    VARCHAR(255),
    content_type VARCHAR(100),
    size_bytes   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispute_evidence_files_dispute_id ON dispute_evidence_files (dispute_id);
