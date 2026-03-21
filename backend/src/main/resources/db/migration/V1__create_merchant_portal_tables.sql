-- V1: Merchant Portal tables (MVP)

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Users (Merchant Portal login)
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);

-- Merchants
CREATE TABLE merchants (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- MerchantUser join table (user ↔ merchant with role)
CREATE TABLE merchant_users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id),
    merchant_id UUID        NOT NULL REFERENCES merchants(id),
    role        VARCHAR(20) NOT NULL,
    invited_by  UUID        REFERENCES users(id),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING_INVITE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, merchant_id)
);

CREATE INDEX idx_merchant_users_user_id     ON merchant_users(user_id);
CREATE INDEX idx_merchant_users_merchant_id ON merchant_users(merchant_id);

-- Refresh tokens
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens(user_id);

-- Invite tokens
CREATE TABLE invite_tokens (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_user_id UUID        NOT NULL REFERENCES merchant_users(id) ON DELETE CASCADE,
    token_hash       VARCHAR(255) NOT NULL UNIQUE,
    expires_at       TIMESTAMPTZ NOT NULL,
    used             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invite_tokens_token_hash ON invite_tokens(token_hash);
