-- 1.5: JWT token_version for immediate access token invalidation on logout.
-- Embedded as a claim in every access token; incremented on logout so all
-- outstanding access tokens for that user are immediately rejected.
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
