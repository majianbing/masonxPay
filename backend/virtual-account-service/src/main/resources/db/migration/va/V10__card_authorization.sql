-- card_authorization: program-manager side authorization decision log.
--
-- One row per authorization decision for a VA-issued card. The issuer side
-- (card-network-sim today, issuer_id = RAIL_SIM) mints authorization_id — an
-- opaque identity unique per distinct authorization within that issuer — and
-- MUST reuse it when retrying a delivery. UNIQUE(issuer_id, authorization_id)
-- makes the decision endpoint idempotent: a duplicate delivery replays the
-- stored decision instead of re-deciding.
--
-- Invariant: decision = 'APPROVED' implies exactly one posted hold journal,
-- referenced by hold_event_id (the ledger source_event_id).
--
-- Raw ISO 8583 vocabulary (DE39 mapping, DE38 auth codes, retransmission
-- matching) stays on the issuer/rail side; stan/rrn here are audit metadata.
--
-- Naming is generic (card_authorization, not vcc_*): the issuing platform will
-- carry more card products than the current prepaid VCC.

CREATE TYPE card_authorization_status AS ENUM (
    'AUTHORIZED', 'DECLINED', 'REVERSED', 'SETTLED', 'EXPIRED'
);

CREATE TABLE card_authorization (
    auth_id          VARCHAR(40)    PRIMARY KEY,      -- snowflake: cauth_{id}
    issuer_id        VARCHAR(32)    NOT NULL,         -- issuer adapter identity, e.g. RAIL_SIM
    authorization_id VARCHAR(64)    NOT NULL,         -- issuer-minted identity for this auth
    card_id          VARCHAR(32)    NOT NULL,
    stan             VARCHAR(6),                      -- audit metadata (ISO 8583 DE11)
    rrn              VARCHAR(12),                     -- audit metadata (DE37)
    amount           NUMERIC(38, 8) NOT NULL CHECK (amount > 0),
    currency         VARCHAR(20)    NOT NULL,
    decision         VARCHAR(10)    NOT NULL,         -- APPROVED / DECLINED
    decline_reason   VARCHAR(30),                     -- machine token; null when approved
    hold_event_id    VARCHAR(64),                     -- ledger source_event_id of the hold journal
    status           card_authorization_status NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Idempotent replay: one decision per issuer authorization identity.
CREATE UNIQUE INDEX uq_card_authorization_identity
    ON card_authorization (issuer_id, authorization_id);

-- Card auth history (audit, future hold-expiry sweep and reversal matching).
CREATE INDEX idx_card_authorization_card ON card_authorization (card_id, created_at DESC);
