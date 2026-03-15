-- Allow weighted traffic splitting across routing rules.
-- Rules with higher weight receive proportionally more traffic when multiple rules match.
ALTER TABLE routing_rules
    ADD COLUMN weight INTEGER NOT NULL DEFAULT 1;
