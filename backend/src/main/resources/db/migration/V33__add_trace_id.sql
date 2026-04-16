-- Phase 2.2: Trace ID propagation
-- Every payment intent and gateway log now carries the X-Request-Id (or generated UUID)
-- that arrived with the originating HTTP request. Enables single-grep trace across all
-- log rows for one payment without any tracing infrastructure.

ALTER TABLE payment_intents
    ADD COLUMN trace_id VARCHAR(36);

ALTER TABLE gateway_logs
    ADD COLUMN trace_id VARCHAR(36);

-- Index for log-grep pattern: find all gateway_logs for a trace, then cross to payment_intents
CREATE INDEX idx_payment_intents_trace_id ON payment_intents (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_gateway_logs_trace_id    ON gateway_logs    (trace_id) WHERE trace_id IS NOT NULL;
