-- Optional TOTP-based MFA for merchant users.
-- mfa_secret        — AES-256-GCM encrypted Base32 TOTP secret
-- mfa_backup_codes  — JSON array of SHA-256-hashed single-use backup codes
ALTER TABLE users
    ADD COLUMN mfa_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_secret       TEXT,
    ADD COLUMN mfa_backup_codes TEXT;
