-- API-key-authenticated requests carry a TEST or LIVE mode.
-- Adding this column lets the dashboard filter logs by the same global mode toggle.
ALTER TABLE gateway_logs ADD COLUMN mode VARCHAR(10);

CREATE INDEX idx_gateway_logs_merchant_mode ON gateway_logs(merchant_id, mode);
