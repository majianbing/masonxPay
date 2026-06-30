-- The dashboard payment list searches payment_read_models.search_text with
-- LOWER(search_text) LIKE '%term%' (DashboardPaymentController), which a
-- to_tsvector GIN index can never satisfy. Replace it with a pg_trgm GIN
-- index on the same expression so substring search uses an index scan
-- instead of a sequential scan.
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

DROP INDEX IF EXISTS idx_payment_read_models_search;

CREATE INDEX idx_payment_read_models_search
    ON payment_read_models USING gin (lower(search_text) gin_trgm_ops);
