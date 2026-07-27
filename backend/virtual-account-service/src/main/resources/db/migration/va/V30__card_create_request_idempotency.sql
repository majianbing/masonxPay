CREATE TABLE card_create_request (
    merchant_id      VARCHAR(64) NOT NULL,
    mode             va_mode NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    card_id          VARCHAR(32) NOT NULL,
    vcc_account_id   VARCHAR(32) NOT NULL,
    hold_account_id  VARCHAR(32) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    error_detail     TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, mode, idempotency_key)
);

CREATE UNIQUE INDEX uq_card_create_request_card
    ON card_create_request (card_id);

CREATE TABLE card_issuer_reconciliation_task (
    task_id                  VARCHAR(64) PRIMARY KEY,
    merchant_id              VARCHAR(64) NOT NULL,
    mode                     va_mode NOT NULL,
    card_id                  VARCHAR(32) NOT NULL,
    issuer_partner_id        VARCHAR(32) NOT NULL,
    external_issuer_card_id  VARCHAR(128) NOT NULL,
    requested_action         VARCHAR(32) NOT NULL,
    target_status            VARCHAR(32) NOT NULL,
    reason                   TEXT,
    idempotency_key          VARCHAR(128) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    error_detail             TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_card_issuer_reconciliation_mutation
    ON card_issuer_reconciliation_task (card_id, requested_action, idempotency_key);
