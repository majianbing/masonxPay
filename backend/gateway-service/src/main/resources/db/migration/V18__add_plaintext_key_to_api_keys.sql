-- Publishable keys (pk_xxx) are safe to store in plaintext — they are public identifiers,
-- not secrets. Secret keys (sk_xxx) remain hashed-only and are never stored in plaintext.
ALTER TABLE api_keys ADD COLUMN plaintext_key TEXT;
